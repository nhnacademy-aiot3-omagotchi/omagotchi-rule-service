package site.omagotchi.ruleservice.distributed.application;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.application.port.EnginePresenceListener;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.application.port.EngineActivePort;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 정적 우선순위 규칙으로 ACTIVE/STANDBY 역할을 판정하고, Activatable 노드에 activate()/deactivate() 지시
 * 규칙: 나보다 우선순위가 높은(priority 값이 낮은) 피어가 하나라도 ONLINE이면 STANDBY, 아니면 ACTIVE
 * 기동 초기 대기(15s) 동안은 역할을 결정하지 않음 - 기동 순서와 무관하게 동일한 결과를 보장하기 위함
 */
@Service
@Slf4j
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineRoleService implements EnginePresenceListener, EngineActivePort {

    private static final long INITIAL_WAIT_MS = 15_000L;
    private static final long GRACE_MS = 1_500L;
    private static final int FAILBACK_CONFIRMATIONS = 2;
    private static final long FAILBACK_CONFIRM_INTERVAL_MS = 5_000L;

    private final EngineDirectoryPort engineDirectoryPort;
    private final EngineProperties engineProperties;
    private final FlowManager flowManager;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final long startedAt;

    // 첫 역할 결정 전까지는 널 - 이 동안 모든 Activatable 노드는 기본값(비활성) 유지
    @Getter
    private volatile EngineRole currentRole;

    // failback 히스테리시스 - 연속으로 STANDBY 판정된 횟수
    private int standbyConfirmCount = 0;

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
        this.taskScheduler.schedule(
                this::reevaluate,
                Instant.now(this.clock).plusMillis(INITIAL_WAIT_MS)
        );
    }

    @Override
    public void onPresenceChanged() {
        this.reevaluate();
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

        EngineRole judged = this.judgeRole();

        // 판정이 지금 롤이랑 같으면 할 것 없음 (기존과 동일)
        if (judged == this.currentRole) {
            this.standbyConfirmCount = 0; // 판정이 안정됐으니 히스테리시스 카운터도 초기화
            return; // 멱등성(실제 전환일 때만 게이트를 건드림)
        }

        // 최초 역할 배정 - 플래핑 방지 대상이 아니므로 즉시 적용
        if (Objects.isNull(this.currentRole)) {
            this.applyRoleChange(judged);
            return;
        }

        // 지금 STANDBY인데 판정이 ACTIVE로 나왔다 = 내가 승격해야 할 것 같다
        // failover 후보이면(STANDBY -> ACTIVE) 순간 오탐 방지 위해 즉시 전환 안 하고 스케줄링으로 confirmFailover를 grace(5초) 뒤 예약(grace 뒤 재확인)
        if (judged == EngineRole.ACTIVE) {
            log.debug("failover 후보 감지 - {}ms 뒤 재확인", GRACE_MS);
            this.taskScheduler.schedule(this::confirmFailover, Instant.now(this.clock).plusMillis(GRACE_MS));

            return; // 여기서 끝 - 아직 currentRole도 안 바꿨고, activate()도 호출 안 함
        }

        // failback 후보(ACTIVE -> STANDBY) - 연속 2회 확인되어야 실제 전환 (플래핑 방지)
        this.standbyConfirmCount++;

        if (this.standbyConfirmCount < FAILBACK_CONFIRMATIONS) {
            log.debug("failback 후보 감지 ({}/{}) - {}ms 뒤 재확인 예약", this.standbyConfirmCount, FAILBACK_CONFIRMATIONS, FAILBACK_CONFIRM_INTERVAL_MS);
            this.taskScheduler.schedule(this::reevaluate, Instant.now(this.clock).plusMillis(FAILBACK_CONFIRM_INTERVAL_MS));

            return; // 아직 1번째면 여기서 끝 - 아무것도 안 바뀜
        }

        this.standbyConfirmCount = 0;
        this.applyRoleChange(judged); // 2번째면 진짜로 STANDBY로 전환
    }

    // grace 경과 후 재확인 - 그 사이 상위 우선순위 피어가 복귀했으면 judgeRole()이 다시 STANDBY로 나와 자동으로 무효화됨
    private synchronized void confirmFailover() {
        if (this.currentRole == EngineRole.ACTIVE) {
            return; // 이미 다른 경로로 ACTIVE 전환됐으면 중복 실행 방지 (멱등)
        }

        EngineRole judged = this.judgeRole(); // 5초 지난 지금 시점에 다시 판정

        if (judged == EngineRole.ACTIVE) {
            this.applyRoleChange(judged); // 5초 뒤에도 여전히 ACTIVE 판정 나면 그제서야 진짜 전환
        } else {
            log.debug("grace 동안 피어 복귀 - failover 취소"); // 그 사이 피어가 돌아왔으면 아무것도 안 하고 끝
        }
    }

    private void applyRoleChange(EngineRole judged) {
        log.info("역할 전환: {} -> {}", this.currentRole, judged);
        this.currentRole = judged;
        this.applyRole(judged);
    }

    private EngineRole judgeRole() {
        int myPriority = this.engineProperties.priority();
        String myId = this.engineProperties.id();

        List<EngineInfo> peers = this.engineDirectoryPort.listEngines();

        List<EngineInfo> higherPriorityPeers = peers.stream()
                .filter(engineInfo -> isHigherPriority(engineInfo, myPriority, myId))
                .toList();

        boolean higherPriorityOnline = higherPriorityPeers.stream()
                .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE);

        if (higherPriorityOnline) {
            return EngineRole.STANDBY;
        }

        boolean higherPriorityAuthFailed = higherPriorityPeers.stream()
                .anyMatch(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.AUTH_FAILED);

        if (higherPriorityAuthFailed) {
            // 상위 우선순위 피어가 응답은 하지만 인증에서 거부됨 - 죽었다는 증거가 아니므로 승격하지 않음
            // 이미 역할이 있으면 그대로 유지, 최초 판정이면 안전하게 STANDBY
            log.warn("[EngineRoleService] 상위 우선순위 피어가 AUTH_FAILED 상태 - 승격 보류 (INTERNAL_SHARED_SECRET 설정 확인 필요, 현재 역할 유지: {})", this.currentRole);

            return Objects.requireNonNullElse(this.currentRole, EngineRole.STANDBY);
        }

        // 최초 판정인데 낮은 우선순위가 피어가 이미 ONLINE+ACTIVE로 활동 중이면, 곧바로 뺏지 않고 STANDBY로 시작
        // (최초 배정은 grace 없이 즉시 적용되므로, 여기서 안 막으면 상대가 강등할 때까지 이중 ACTIVE 구간이 생김)
        // OFFLINE 피어에 남아있는 옛 engineRole 잔상에 낚이지 않도록 presenceStatus == ONLINE도 같이 확인
        if (Objects.isNull(this.currentRole)) {
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
        this.flowManager.applyActivationState(role == EngineRole.ACTIVE);
    }
}
