package site.omagotchi.ruleservice.distributed.application;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.application.port.EnginePresenceListener;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.application.port.EngineActivePort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 정적 우선순위 규칙으로 ACTIVE/STANDBY 역할을 판정하고, Activatable 노드에 activate()/deactivate() 지시
 * 규칙: 나보다 우선순위가 높은 피어가 스스로 ACTIVE라고 보고하면 STANDBY, 그 피어가 AUTH_FAILED면 승격 보류, 아니면 ACTIVE
 * 상위 피어가 ONLINE인데 아직 자기 역할을 못 정한 경우(재기동 직후 등)는 그 자체로 양보하지 않음
 * 단, 나도 아직 첫 판정 전(콜드부트)이면서 상대도 못 정했다면 안전하게 기존 우선순위 규칙(ONLINE이면 양보)으로 폴백
 * 기동 초기 대기(15s) 동안은 역할을 결정하지 않음 - 기동 순서와 무관하게 동일한 결과를 보장하기 위함
 * <p>
 * exactly-one ACTIVE 보장 범위: 프로세스 장애(크래시, 재기동)와 대칭적 네트워크 단절(피어와 완전히 끊김)까지는 보장함
 * 비대칭 단절(예: 내부 HTTP 폴링만 끊기고 MQTT 구독은 살아있는 경우)은 이 메커니즘만으로 막을 수 없음 -
 * A는 자기가 살아있다고 여겨 계속 ACTIVE를 유지하고, B는 A를 OFFLINE으로 오판해 승격하여 둘 다 ACTIVE가 될 수 있음
 * 이 경우를 완전히 막으려면 두 엔진 밖의 중재점(분산 lease + fencing token, 브로커의 단일 소유권 등)이 필요하며 현재 범위 밖
 * (TwoEngineDualActiveSimulationTest의 비대칭 단절 테스트, 하위 파이프라인의 멱등성 처리와 함께 운영 계약으로 다룸)
 */
@Service
@Slf4j
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineRoleService implements EnginePresenceListener, EngineActivePort {

    // 테스트에서도 프로덕션과 동일한 값을 참조하도록 패키지 프라이빗으로
    static final long INITIAL_WAIT_MS = 15_000L;
    static final long GRACE_MS = 1_500L;
    private static final long RECONCILE_INTERVAL_SECONDS = 30;

    private static final long ACTIVATION_RETRY_DELAY_MS = 3_000L; // 실패 시 한 번 재시도 할 때 사용
    static final long FAILBACK_CONFIRM_INTERVAL_MS = 5_000L;
    static final long COLD_BOOT_RECHECK_MS = 1_500L;

    private final EngineDirectoryPort engineDirectoryPort;
    private final EngineProperties engineProperties;
    private final FlowManager flowManager;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final long startedAt;

    // 첫 역할 결정 전까지는 널 - 이 동안 모든 Activatable 노드는 기본값(비활성) 유지
    @Getter
    private volatile EngineRole currentRole;

    // fallback 후보로 처음 감지된 시각 - null이면 후보 아님 (경과시간 기준 히스테리시스, 호출 횟수 아님)
    private Instant standbyCandidateSince;

    public EngineRoleService(EngineDirectoryPort engineDirectoryPort,
                             EngineProperties engineProperties,
                             FlowManager flowManager,
                             @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") TaskScheduler taskScheduler, // IDE 오탐 이슈로 붙임.
                             Clock clock) {

        this.engineDirectoryPort = engineDirectoryPort;
        this.engineProperties = engineProperties;
        this.flowManager = flowManager;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.startedAt = this.clock.millis();
    }

    /**
     * 기동 초기 대기가 끝나는 시점에 첫 역할 판정을 강제로 한 번 실행
     * 그 전까지 피어 상태 변화가 없으면 onPresenceChanged()가 한 번도 호출되지 않을 수 있어서 별도로 예약함
     */
    @PostConstruct
    public void scheduleInitialEvaluation() {
        log.info("[EngineRoleService] 기동 - {}ms 뒤 첫 역할 판정 예정 (id = {}, priority = {})", INITIAL_WAIT_MS, this.engineProperties.id(), this.engineProperties.priority());

        this.taskScheduler.schedule(
                this::reevaluate,
                Instant.now(this.clock).plusMillis(INITIAL_WAIT_MS)
        );
    }

    @Override
    public void onPresenceChanged() {
        this.reevaluate();
    }

    /**
     * 정기 자기 치유 - 두 가지를 함께 수행
     * 1) 역할 재판정: 이 클래스는 onPresenceChanged() 알림에만 의존해 재판정하는데, 피어 상태가 그대로면 알림이 오지 않음
     * 예를 들어 상위 엔진이 재기동하면서 "이미 활동 중인 하위 피어에게 양보"(judgeRole의 최초 배정 가드)로 STANDBY로 시작하면,
     * 그 뒤 피어에 아무 변화가 없어 알림이 끊기고 우선순위가 역전된 채 영영 고착됨
     * -> 주기적으로 판정을 다시 돌려서 엣지 트리거가 흘린 상황을 흡수함 (판정이 그대로면 아무 일도 하지 않음)
     * 2) 노드 게이트 재적용: FlowManager.applyCurrentActivationState()의 isSelfActive() 조회와 이 클래스의 역할 전환이
     * 락 없이 교차하면 게이트가 실제 역할과 어긋난 채 남을 수 있음
     * synchronized 필수 - 없으면 읽은 낡은 currentRole을 진행 중인 전환 뒤에 뒤늦게 밀어 넣어 방금 끝난 전환을 되돌림
     * flowManager.applyActivationState()는 상대 엔진에게 네트워크 호출을 하지 않는 로컬 동작이라 락을 쥔 채 호출해도 순환 대기 없음
     * activate/deactivate 전부 멱등하므로 이미 올바른 상태인 노드에는 비용이 거의 없음
     */
    @Scheduled(fixedDelay = RECONCILE_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
    public synchronized void reconcile() {
        this.reevaluate();

        if (Objects.nonNull(this.currentRole)) {
            this.applyRole(this.currentRole);
        }
    }

    @Override
    public boolean isSelfActive() {
        return this.currentRole == EngineRole.ACTIVE;
    }

    // package-private (테스트 클래스에서 @PostConstruct 스케줄링을 기다리지 않고 직접 호출해서 검증할 수 있도록)
    synchronized void reevaluate() {
        if (this.clock.millis() - this.startedAt < INITIAL_WAIT_MS) {
            log.debug("기동 초기 대기 중 - 역할 판정 보류");
            return;
        }

        // 자가 치유: 나도 액티브인데 상위 우선순위 피어도 액티를 보고하면 (최신 폴링 기준)
        // 확실한 이중 액티브 신호이므로, 일반 failback 히스테리시스를 기다리지 않고 즉시 강등
        if (this.currentRole == EngineRole.ACTIVE && this.higherPriorityPeerReportsActive()) {
            log.warn("[EngineRoleService] 상위 우선순위 피어도 ACTIVE를 보고함 (이중 ACTIVE 감지) - 즉시 STANDBY로 강등");

            this.standbyCandidateSince = null;
            this.applyRoleChange(EngineRole.STANDBY);

            return;
        }

        List<EngineInfo> peers = this.engineDirectoryPort.listEngines();
        EngineRole judged = this.judgeRole(peers);

        // 판정이 지금 롤이랑 같으면 할 것 없음 (기존과 동일)
        if (judged == this.currentRole) {
            this.standbyCandidateSince = null; // 판정이 안정됐으니 히스테리시스 카운터도 초기화
            return; // 멱등성(실제 전환일 때만 게이트를 건드림)
        }

        // 최초 역할 배정 - 플래핑 방지 대상이 아니므로 즉시 적용
        if (Objects.isNull(this.currentRole)) {
            this.applyRoleChange(judged);

            // 상위 우선순위 피어가 없는데 STANDBY로 시작했다 == 이미 활동중인 하위 피어에게 양보한 것(judgeRole의 콜드부트 가드)
            // 이 상태에서 피어에 변화가 없으면 onPresenceChanged() 알림이 안 와서 reconcile(30초)이 돌 때까지 승격이 밀림
            // -> 짧게 뒤 재판정을 예약해서, 정규 failover 경로(grace 재확인)를 타고 제때 승격하게 함
            // judgeRole()과 같은 스냅샷(peers)을 써야 함. 따로 조회하면 그 사이 디렉터리가 바뀌어 두 판단이 어긋날 수 있음
            if (judged == EngineRole.STANDBY && !this.hasHigherPriorityPeer(peers)) {
                this.taskScheduler.schedule(this::reevaluate, Instant.now(this.clock).plusMillis(COLD_BOOT_RECHECK_MS));
            }

            return;
        }

        // 지금 STANDBY인데 판정이 ACTIVE로 나왔다 = 내가 승격해야 할 것 같다
        // failover 후보이면(STANDBY -> ACTIVE) 순간 오탐 방지 위해 즉시 전환 안 하고 스케줄링으로 confirmFailover를 grace(1.5초) 뒤 예약(grace 뒤 재확인)
        if (judged == EngineRole.ACTIVE) {
            log.debug("failover 후보 감지 - {}ms 뒤 재확인", GRACE_MS);
            this.taskScheduler.schedule(this::confirmFailover, Instant.now(this.clock).plusMillis(GRACE_MS));

            return; // 여기서 끝 - 아직 currentRole도 안 바꿨고, activate()도 호출 안 함
        }

        // failback 후보(ACTIVE -> STANDBY) - 최초 감지 이후 FAILBACK_CONFIRM_INTERVAL_MS가 지나야 실제 전환 (플래핑 방지)
        // 카운터가 아니라 경과 시간으로 판단 - 그래야 reevaluate()가 짧은 간격으로 여러 번 몰려도(피어 3대 이상 등) 실제로 5초를 기다림
        Instant now = Instant.now(this.clock);

        if (Objects.isNull(this.standbyCandidateSince)) {
            this.standbyCandidateSince = now;
        }

        long elapsedMs = Duration.between(this.standbyCandidateSince, now).toMillis();

        if (elapsedMs < FAILBACK_CONFIRM_INTERVAL_MS) {
            long remainingMs = FAILBACK_CONFIRM_INTERVAL_MS - elapsedMs;
            log.debug("[EngineRoleService] failback 후보 감지 (경과 {}ms/{}ms) - {}ms 뒤 재확인 예약", elapsedMs, FAILBACK_CONFIRM_INTERVAL_MS, remainingMs);
            this.taskScheduler.schedule(this::reevaluate, now.plusMillis(remainingMs));

            return; // 아직 시간이 안 지났으면 여기서 끝 - 아무것도 안 바뀜
        }

        this.standbyCandidateSince = null;
        this.applyRoleChange(judged); // 시간이 다 지났으면 진짜로 STANDBY로 전환
    }

    private boolean higherPriorityPeerReportsActive() {
        int myPriority = this.engineProperties.priority();
        String myId = this.engineProperties.id();

        return this.engineDirectoryPort.listEngines().stream()
                .filter(engineInfo -> isHigherPriority(engineInfo, myPriority, myId))
                .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE
                        && engineInfo.engineRole() == EngineRole.ACTIVE);
    }

    // judgeRole()이 STANDBY로 판정할 때 근거로 삼는 상태(ONLINE/AUTH_FAILED)만 '상위 피어 있음'으로 침
    // OFFLINE은 judgeRole()도 무시하므로 여기서도 무시해야 함. 안 그러면 OFFLINE으로 남은 상위 피어 잔상 때문에, 하위 피어에게 양보한 뒤의 조기 재판정 예약이 조용히 생략됨
    private boolean hasHigherPriorityPeer(List<EngineInfo> peers) {
        int myPriority = this.engineProperties.priority();
        String myId = this.engineProperties.id();

        return peers.stream()
                .anyMatch(engineInfo -> isHigherPriority(engineInfo, myPriority, myId)
                        && engineInfo.presenceStatus() != PresenceStatus.OFFLINE);
    }

    // grace 경과 후 재확인 - 그 사이 상위 우선순위 피어가 복귀했으면 judgeRole()이 다시 STANDBY로 나와 자동으로 무효화됨
    private synchronized void confirmFailover() {
        if (this.currentRole == EngineRole.ACTIVE) {
            return; // 이미 다른 경로로 ACTIVE 전환됐으면 중복 실행 방지 (멱등)
        }

        EngineRole judged = this.judgeRole(this.engineDirectoryPort.listEngines()); // grace(1.5초) 지난 지금 시점에 다시 판정

        if (judged == EngineRole.ACTIVE) {
            this.applyRoleChange(judged); // grace(1.5초) 뒤에도 여전히 ACTIVE 판정 나면 그제서야 진짜 전환
        } else {
            log.debug("grace 동안 피어 복귀 - failover 취소"); // 그 사이 피어가 돌아왔으면 아무것도 안 하고 끝
        }
    }

    private void applyRoleChange(EngineRole judged) {
        log.info("[EngineRoleService] 역할 전환: {} -> {} (id = {}, priority = {})", this.currentRole, judged, this.engineProperties.id(), this.engineProperties.priority());
        this.currentRole = judged;
        this.applyRole(judged);
    }

    private EngineRole judgeRole(List<EngineInfo> peers) {
        int myPriority = this.engineProperties.priority();
        String myId = this.engineProperties.id();

        List<EngineInfo> higherPriorityPeers = peers.stream()
                .filter(engineInfo -> isHigherPriority(engineInfo, myPriority, myId))
                .toList();

        // 상위 피어가 스스로 액티브라고 보고 -> 확실한 신호 -> 즉시 양보
        // (리팩터링 전에는 온라인이기만 하면 양보했음.
        // 그렇게 하면 상위 엔진이 재기동 중이라 자기 역할을 아직 못 정했을 뿐인데도 미리 강등해버려서 아무도 액티브가 아닌 공백이 생김
        // - 상위가 실제로 액티브를 선언할 때까지 기다림)
        boolean higherPriorityReportsActive = higherPriorityPeers.stream()
                .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE
                        && engineInfo.engineRole() == EngineRole.ACTIVE);

        if (higherPriorityReportsActive) {
            return EngineRole.STANDBY;
        }

        boolean higherPriorityAuthFailed = higherPriorityPeers.stream()
                .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.AUTH_FAILED);

        if (higherPriorityAuthFailed) {
            // 상위 우선순위 피어가 응답은 하지만 인증에서 거부됨 - 죽었다는 증거가 아니므로 승격하지 않음
            log.warn("[EngineRoleService] 상위 우선순위 피어가 AUTH_FAILED 상태 - 승격 보류, STANDBY로 판정 (INTERNAL_SHARED_SECRET 설정 확인 필요)");
            return EngineRole.STANDBY;
        }

        // 아래 두 가드는 전부 '나의 첫 판정(콜드부트)'에서만 의미가 있음
        // - 한 번 역할이 정해진 뒤에는 상위 피어의 명시적 액티브 보고로만 판단하므로, 상대가 온라인인데 role만 모른다고 흔들리지 않음
        if (Objects.isNull(this.currentRole)) {
            // 상위 피어가 연결은 되는데 아직 자기 역할을 못 정한 경우 (재기동 후 15초 대기중 등)
            // - 그 자체는 상위 피어가 액티브란 증거는 아니지만, 나도 처음 판정하는 중이라 상대가 뭘 할지 전혀 모름
            // 이 상황에서 내가 먼저 액티브를 선언해버리면, 상대도 똑같이 몰라서 액티브를 선언할 수 있어서 이중액티브가 됨
            // 따라서, 진짜 애매한 콜드부트(둘 다 첫 판정) 동시 기동 상황에서만 안전하게 기존 우선순위 규칙(온라인이면 양보)으로 폴백
            boolean higherPriorityOnlineWithUnknownRole = higherPriorityPeers.stream()
                    .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE
                            && Objects.isNull(engineInfo.engineRole()));

            if (higherPriorityOnlineWithUnknownRole) {
                return EngineRole.STANDBY;
            }

            // 최초 판정인데 낮은 우선순위가 피어가 이미 ONLINE+ACTIVE로 활동 중이면, 곧바로 뺏지 않고 STANDBY로 시작
            // (최초 배정은 grace 없이 즉시 적용되므로, 여기서 안 막으면 상대가 강등할 때까지 이중 ACTIVE 구간이 생김)
            // OFFLINE 피어에 남아있는 옛 engineRole 잔상에 낚이지 않도록 presenceStatus == ONLINE도 같이 확인
            boolean onlinePeerAlreadyActive = peers.stream()
                    .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE
                            && engineInfo.engineRole() == EngineRole.ACTIVE);

            if (onlinePeerAlreadyActive) {
                return EngineRole.STANDBY;
            }
        }

        return EngineRole.ACTIVE;
    }

    private boolean isHigherPriority(EngineInfo otherEngineInfo, int myPriority, String myId) {
        if (otherEngineInfo.priority() != myPriority) {
            return otherEngineInfo.priority() < myPriority; // priority는 낮을수록 우선
        }

        // 우선순위 중복 - engineId 사전순이 빠른 쪽이 우선
        log.warn("[{}] 우선순위({}) 중복 - engineId 사전순으로", otherEngineInfo.engineId(), myPriority);
        return otherEngineInfo.engineId().compareTo(myId) < 0;
    }

    private void applyRole(EngineRole role) {
        boolean allSucceeded = this.flowManager.applyActivationState(role == EngineRole.ACTIVE);

        if (!allSucceeded) {
            log.warn("[EngineRoleService] 일부 노드의 활성화 상태 전환 실패 - {}ms 후 재시도 (role = {})", ACTIVATION_RETRY_DELAY_MS, role);
            this.taskScheduler.schedule(
                    () -> this.retryApplyRole(role), Instant.now(this.clock).plusMillis(ACTIVATION_RETRY_DELAY_MS)
            );
        }
    }

    // 실패한 노드 재시도
    private synchronized void retryApplyRole(EngineRole role) {

        // 예약된 시점에 역할이 이미 또 바뀌었으면(다른 전환이 처리됐으면) 낡은 재시도이므로 건너뜀
        if (this.currentRole != role) {
            return;
        }

        boolean allSucceeded = this.flowManager.applyActivationState(role == EngineRole.ACTIVE);

        if (!allSucceeded) {
            log.error("[EngineRoleService] 재시도에도 일부 노드 활성화 상태 전환 실패 - 다음 역할 전까지 수동 확인 필요 (role = {})", role);
        }
    }
}
