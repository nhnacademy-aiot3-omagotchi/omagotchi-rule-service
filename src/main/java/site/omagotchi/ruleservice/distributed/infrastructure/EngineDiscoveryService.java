package site.omagotchi.ruleservice.distributed.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.application.port.EnginePresenceListener;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Eureka에 등록된 rule-service 피어 목록을 조회하고, 각 피어의 GET /api/v1/internal/engines/self를 직접 폴링해서 ONLINE/OFFLINE을 판정
 * 생존 판정(= 역할 판정의 근거)은 오직 이 폴링 결과로만 하고, Eureka의 lease/eviction은 승격·강등 결정에 일절 쓰지 않음
 * Eureka는 (1) 피어 주소 해결과 (2) 이미 OFFLINE으로 판정된 피어를 목록에서 정리할지 판단하는 보조 근거로만 사용
 * - (2)는 역할 판정에 영향을 주지 않음: judgeRole()이 OFFLINE 피어를 애초에 세지 않기 때문
 * - Eureka가 여전히 그 피어를 알고 있으면 정리를 보류하므로, Eureka 오판은 "덜 지우는" 안전한 방향으로만 작용
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineDiscoveryService implements EngineDirectoryPort {

    // 테스트에서도 프로덕션과 동일한 값을 참조하도록 패키지 프라이빗으로 노출
    static final long OFFLINE_THRESHOLD_MS = 3_000L;

    // Eureka에서 사라지고 OFFLINE으로 이만큼 지나면 피어 목록에서 제거
    // Eureka 기본 lease 만료(90초)보다 넉넉히 길게 잡아, 일시적 등록 공백을 스케일다운으로 오해하지 않도록 함
    static final long PEER_EXPIRY_MS = 300_000L;

    private final DiscoveryClient discoveryClient;
    private final RestClient engineInternalRestClient;
    private final String applicationName;
    private final String selfEngineId;
    private final List<EnginePresenceListener> enginePresenceListeners;
    private final Clock clock;
    private final Set<String> authFailureWarned = ConcurrentHashMap.newKeySet(); // 동시성 문제 X

    // ENGINE_ID 중복 경고를 매초 반복해서 찍지 않기 위한 플래그
    private volatile boolean duplicateSelfIdWarned = false;

    // 신원 불일치 경고를 피어당 한 번만 남기기 위한 셋 (1초마다 반복 로깅 방지)
    private final Set<String> identityMismatchWarned = ConcurrentHashMap.newKeySet();

    // peerEngineId -> 현재 알려진 정보(판정된 presenceStatus 포함)
    private final Map<String, EngineInfo> knownEngines = new ConcurrentHashMap<>();

    // peerEngineId -> 마지막으로 폴링에 성공한 시각(첫 발견 시점엔 유예를 위해 지금 시각으로 시드)
    private final Map<String, Long> lastPolledSuccessAt = new ConcurrentHashMap<>();

    // peerEngineId -> 마지막으로 403(AUTH_FAILED) 응답을 받은 시각
    // 이 시각 이후로 403조차 못 받고 연결 자체가 실패하면, 인증 문제가 아니라 진짜로 죽은 것으로 판단
    private final Map<String, Long> lastAuthFailedAt = new ConcurrentHashMap<>();

    // peerEngineId -> 마지막으로 Eureka registry에 나타난 시각
    private final Map<String, Long> lastSeenInRegistryAt = new ConcurrentHashMap<>();

    public EngineDiscoveryService(DiscoveryClient discoveryClient,
                                  RestClient engineInternalRestClient,
                                  @Value("${spring.application.name}") String applicationName,
                                  @Value("${engine.id}") String selfEngineId,
                                  @Lazy List<EnginePresenceListener> enginePresenceListeners,
                                  Clock clock) {

        this.discoveryClient = discoveryClient;
        this.engineInternalRestClient = engineInternalRestClient;
        this.applicationName = applicationName;
        this.selfEngineId = selfEngineId;
        this.enginePresenceListeners = enginePresenceListeners;
        this.clock = clock;
    }

    /**
     * 현재까지 파악된 피어 목록의 스냅샷 (자기 자신은 포함하지 않음)
     * 실제 조회, 생존 판정은 pollPeers()가 주기적으로 수행하고, 이 메서드는 그 결과를 복사해서 돌려주기만 함
     */
    @Override
    public List<EngineInfo> listEngines() {
        return List.copyOf(this.knownEngines.values());
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    public void pollPeers() {
        Optional<List<ServiceInstance>> instances = this.fetchRegistry();
        Set<String> polledThisCycle = new HashSet<>();

        // |= (비트 OR) 사용
        // notableChangeDetected = notableChangeDetected || this.pollFallbackPeers(...) 이렇게 쓰면,
        // notableChangeDetected가 이미 true인 순간 뒤 조건은 평가 자체를 건너뛰는 단락 평가가 일어나서 pollFallbackPeers()/judgePresence()가 아예 호출 안 될 수 있음
        boolean notableChangeDetected = this.pollDiscoveredPeers(instances.orElse(List.of()), polledThisCycle);
        notableChangeDetected |= this.pollFallbackPeers(polledThisCycle);
        notableChangeDetected |= this.judgePresence();

        // Eureka 응답을 못 받은 주기에는 만료도, 종복ID 검사도 건너뜀
        // (조회 실패를 '지금은 중복이 아니다'로 오해하면 플래그가 잘못 리셋되어, 복구 후 여전히 같은 중복인데도 재경고가 나감)
        if (instances.isPresent()) {
            this.checkForDuplicateSelfId(instances.get());
            notableChangeDetected |= this.expireLongGonePeers();
        } else {
            this.pauseExpiry(); // 관측 불가 구간에는 카운트다운을 되돌림
        }

        if (notableChangeDetected) {
            this.enginePresenceListeners.forEach(EnginePresenceListener::onPresenceChanged);
        }
    }

    // ENGINE_ID가 나와 같은 등록이 2개 이상이면(예: compose 파일 복붙 실수)
    // pollDiscoveredPeers()의 자기 자신 필터가 둘 다 '나 자신'으로 오인해 조용히 걸러버리고,
    // 결과적으로 양쪽 다 '피어가 없다'라고 착각해 둘 다 ACTIVE가 될 수 있음
    // Eureka가 자기 자신의 등록도 함꼐 돌려준다는 전제 - register-with-eureka=false면 내 등록이 목록에 없어 이 검사는 동작하지 않음
    // (그 설정에서는 피어끼리 서로를 발견할 수 없어 이중화 자체가 성립하지 않으므로 문제는 X)
    private void checkForDuplicateSelfId(List<ServiceInstance> instances) {
        long selfIdCount = instances.stream()
                .filter(instance -> this.selfEngineId.equals(instance.getMetadata().get("engine-id")))
                .count();

        if(selfIdCount > 1) {
            if(!this.duplicateSelfIdWarned) {
                log.error("[EngineDiscoveryService] ENGINE_ID '{}'로 등록된 인스턴스가 {}개 발견됨 - 설정 오류로 서로를 자기 자신으로 오인해 이중화가 깨질 수 있습니다. 각 엔진의 ENGINE_ID가 고유한지 확인하세요.",
                        this.selfEngineId, selfIdCount);
                this.duplicateSelfIdWarned = true;
            }
        } else {
            this.duplicateSelfIdWarned = false; // 복구되면 다음 재발 때 다시 경고할 수 있도록
        }
    }

    /**
     * Eureka를 조회하지 못한 구간은 '피어가 사라졌다'라는 증거가 아니므로 만료 카운트다운을 되돌림
     * 이걸 안 하면 조회 불가 시간이 그대로 만료 시간에 들어가, 장애 복구 직후 첫 조회에서 곧바로 지워짐
     * 결과적으로 만료는 "Eureka가 계속 응답하는 동안 연속으로 부재가 확인된 시간"으로만 누적됨
     */
    private void pauseExpiry() {
        long now = this.clock.millis();
        this.knownEngines.keySet().forEach(peerEngineId -> this.lastSeenInRegistryAt.put(peerEngineId, now));
    }

    // 조회 실패와 '정말로 인스턴스가 없음'을 구분하기 위해 Optional로 감쌈
    private Optional<List<ServiceInstance>> fetchRegistry() {
        try {
            return Optional.of(this.discoveryClient.getInstances(this.applicationName));
        } catch (Exception e) {
            log.warn("[EngineDiscoveryService] Eureka 피어 목록 조회 실패 - 이번 주기는 건너뜁니다", e);
            return Optional.empty();
        }
    }

    // Eureka가 이번 주기에 돌려준 인스턴스들을 폴링
    // 새 피어 발견 또는 role/presence 변화가 있으면 true
    private boolean pollDiscoveredPeers(List<ServiceInstance> instances, Set<String> polledThisCycle) {
        boolean notableChangeDetected = false;

        // 반복문 안에서 예외가 새어 나가면 pollPeers()가 통째로 중단되어 judgePresence()까지 건너뛰고, 죽은 피어가 계속 ONLINE으로 남아 failover가 막힘
        try {
            for (ServiceInstance instance : instances) {
                String peerEngineId = instance.getMetadata().get("engine-id");

                if (Objects.isNull(peerEngineId) || peerEngineId.equals(this.selfEngineId)) {
                    continue;
                }

                this.lastSeenInRegistryAt.put(peerEngineId, this.clock.millis()); // 만료 판정의 기준점

                // "새 피어 발견"을 별도로 감지해서 항상 알림
                if (!this.knownEngines.containsKey(peerEngineId)) {
                    notableChangeDetected = true; // 처음 보는 피어 - 상태와 무관하게 존재 자체를 알려야 함
                }

                if (this.pollOne(peerEngineId, instance)) {
                    notableChangeDetected = true;
                }

                polledThisCycle.add(peerEngineId);
            }
        } catch (Exception e) {
            log.warn("[EngineDiscoveryService] 피어 폴링 중 예기치 못한 오류 - 이번 주기의 남은 인스턴스는 건너뜁니다", e);
        }

        return notableChangeDetected;
    }

    // Eureka가 이번 주기에 못 돌려준(예: discovery-service 재배포로 registry가 잠깐 비는 상황) 피어도 이미 알고 있는 주소로 직접 폴링을 이어감
    // Eureka 장애 자체가 승격 트리거가 되지 않도록
    private boolean pollFallbackPeers(Set<String> polledThisCycle) {
        boolean notableChangeDetected = false;

        for (Map.Entry<String, EngineInfo> entry : this.knownEngines.entrySet()) {
            String peerEngineId = entry.getKey();

            if (polledThisCycle.contains(peerEngineId)) {
                continue;
            }

            EngineInfo known = entry.getValue();
            ServiceInstance fallbackInstance = new DefaultServiceInstance(
                    peerEngineId,
                    this.applicationName,
                    known.host(),
                    known.port(),
                    false
            );

            if (this.pollOne(peerEngineId, fallbackInstance)) {
                notableChangeDetected = true;
            }
        }

        return notableChangeDetected;
    }

    /**
     * Eureka에서도 사라지고 OFFLINE 상태로 충분히 오래 지난 피어를 목록에서 제거
     * (의도적) 스케일다운(2대 -> 1대) 후에도 죽은 주소를 매초 폴링하고 topology가 영구 DEGRADED로 남는 것을 방지
     * 호출 자체가 "이번 주기에 Eureka 조회가 성공했을 때"로 제한되므로, discovery-service 장애로는 만료되지 않음
     */
    private boolean expireLongGonePeers() {
        long now = this.clock.millis();
        boolean removedAny = false;

        for (Map.Entry<String, EngineInfo> entry : this.knownEngines.entrySet()) {
            String peerEngineId = entry.getKey();

            if (entry.getValue().presenceStatus() != PresenceStatus.OFFLINE) {
                continue; // 살아있거나(ONLINE) 인증만 막힌(AUTH_FAILED) 피어는 제거 대상이 아님
            }

            long lastInRegistry = this.lastSeenInRegistryAt.getOrDefault(peerEngineId, now);

            if (now - lastInRegistry < PEER_EXPIRY_MS) {
                continue;
            }

            log.warn("[{}] Eureka에서 사라지고 OFFLINE으로 {}ms 이상 지속 - 피어 목록에서 제거", peerEngineId, PEER_EXPIRY_MS);
            this.removePeer(peerEngineId);
            removedAny = true;
        }

        return removedAny;
    }

    // 피어가 딸린 추적 상태를 한꺼번에 정리 (남겨두면 같은 id로 재등장할 때 낡은 값이 판정에 섞임)
    private void removePeer(String peerEngineId) {
        this.knownEngines.remove(peerEngineId);
        this.lastPolledSuccessAt.remove(peerEngineId);
        this.lastAuthFailedAt.remove(peerEngineId);
        this.lastSeenInRegistryAt.remove(peerEngineId);
        this.authFailureWarned.remove(peerEngineId);
        this.identityMismatchWarned.remove(peerEngineId);
    }

    private boolean pollOne(String peerEngineId, ServiceInstance instance) {
        try {
            PeerSelfInfo response = this.engineInternalRestClient.get()
                    .uri("http://{host}:{port}/api/v1/internal/engines/self", instance.getHost(), instance.getPort())
                    .retrieve()
                    .body(PeerSelfInfo.class);

            // 응답한 엔진이 Eureka 메타데이터가 약속한 그 엔진인지 확인
            // (레지스트리 위장 등록, 메타데이터 오설정을 걸러냄 - 이 응답의 priority/engineRole이 그대로 쓰이므로)
            if (Objects.isNull(response) || !peerEngineId.equals(response.engineId())) {
                if (this.identityMismatchWarned.add(peerEngineId)) {
                    log.error("[{}] 폴링 응답의 engineId가 Eureka 메타데이터와 다름 (응답 = {}, host = {}, port = {}) - 신뢰하지 않고 폴링 실패로 처리",
                            peerEngineId, Objects.isNull(response) ? null : response.engineId(), instance.getHost(), instance.getPort());
                }

                // 믿을 수 없는 응답 = 그 피어에 닿지 못한 것과 같게 취급
                // (사칭 인스턴스가 응답한다고 해서 진짜 피어가 살아있는 것처럼 보이면 안 되니까)
                return this.markFailure(peerEngineId, instance);
            }

            this.identityMismatchWarned.remove(peerEngineId);
            this.authFailureWarned.remove(peerEngineId); // 복구되면 다음 실패 때 다시 경고할 수 있도록 초기화
            this.lastAuthFailedAt.remove(peerEngineId); // AUTH_FAILED 추적 상태도 정리

            EngineInfo existing = this.knownEngines.get(peerEngineId);
            PresenceStatus previousStatus = Objects.nonNull(existing)
                    ? existing.presenceStatus()
                    : PresenceStatus.ONLINE; // 첫 발견 유예
            EngineRole previousRole = Objects.nonNull(existing)
                    ? existing.engineRole()
                    : null;

            // AUTH_FAILED였다가 성공했으면 즉시 ONLINE으로 - 이후 타임아웃 판정은 judgePresence()가 이어받음
            PresenceStatus statusToCarry = previousStatus == PresenceStatus.AUTH_FAILED
                    ? PresenceStatus.ONLINE
                    : previousStatus;

            this.knownEngines.put(peerEngineId, new EngineInfo(
                    response.engineId(),
                    instance.getHost(),
                    instance.getPort(),
                    response.priority(),
                    response.startedAt(),
                    statusToCarry,
                    response.engineRole() // 폴링 성공 시엔 피어가 방금 보고한 engineRole 그대로 반영
            ));

            this.lastPolledSuccessAt.put(peerEngineId, this.clock.millis());

            // presenceStatus 전이(특히 AUTH_FAILED -> ONLINE)와 피어 role 변화 모두 리스너에게 알려야 함
            // judgePresence()는 이미 ONLINE으로 덮인 뒤를 보므로, AUTH_FAILED 복구를 잡아내지 못함
            // (안 그러면 failback 대기 중인 엔진이 상대가 실제로 강등되는 순간을 영영 못 보고 재판정 기회를 잃음)
            return previousStatus != statusToCarry
                    || previousRole != response.engineRole();

        } catch (HttpClientErrorException.Forbidden e) {
            // 상대가 살아서 응답은 했지만 인증을 거부함 - "죽었다"는 증거가 아니라 설정 오류일 가능성이 높음
            // 타임아웃 기반 OFFLINE 판정 경로를 안 타고, 즉시 AUTH_FAILED로 확정
            if (this.authFailureWarned.add(peerEngineId)) { // 이 피어에 대해 처음 경고하는 거면 true일 것임
                log.warn("[{}] 폴링 인증 실패 (host = {}, port = {}) - INTERNAL_SHARED_SECRET이 양쪽 엔진에 동일하게 설정됐는지 확인하세요", peerEngineId, instance.getHost(), instance.getPort());
            }

            this.lastAuthFailedAt.put(peerEngineId, this.clock.millis()); // 이 403 자체가 아직 살아있다는 증거

            EngineInfo known = this.knownEngines.get(peerEngineId);

            // judgePresence()가 AUTH_FAILED를 건너뛰므로, 이 전이는 여기서 직접 알리지 않으면 리스너가 영영 못 봄
            boolean statusChanged = Objects.isNull(known)
                    || known.presenceStatus() != PresenceStatus.AUTH_FAILED;

            // 이미 아는 피어면 priority/startedAt/engineRole은 그대로 두고 상태만 바꿈
            // (폴백 폴링 인스턴스는 metadata가 비어 있어서 새로 파싱하면 priority가 유실되고,
            // 그러면 상위 우선순위 피어가 최하위로 둔갑해서 AUTH_FAILED 승격 보류 로직이 무력화됨)
            this.knownEngines.compute(peerEngineId, (k, existing) -> Objects.nonNull(existing)
                    ? existing.withPresenceStatus(PresenceStatus.AUTH_FAILED)
                    : new EngineInfo(
                    peerEngineId,
                    instance.getHost(),
                    instance.getPort(),
                    parsePriority(instance.getMetadata().get("engine-priority")),
                    0L,
                    PresenceStatus.AUTH_FAILED,
                    null
            ));

            // lastPolledSuccessAt은 일부러 안 건드림 - judgePresence()가 AUTH_FAILED는 타임아웃 판정에서 제외하므로 무의미
            return statusChanged;

        } catch (Exception e) {
            log.debug("[{}] 폴링 실패 (host = {}, port = {})", peerEngineId, instance.getHost(), instance.getPort(), e);

            return this.markFailure(peerEngineId, instance); // AUTH_FAILED -> OFFLINE 전환 여부를 그대로 전환
        }
    }

    // presenceStatus가 실제로 바뀌었으면(AUTH_FAILED -> OFFLINE) true
    private boolean markFailure(String peerEngineId, ServiceInstance instance) {
        EngineInfo known = this.knownEngines.get(peerEngineId);

        if (Objects.nonNull(known) && known.presenceStatus() == PresenceStatus.AUTH_FAILED) {
            Long lastAuthFailed = this.lastAuthFailedAt.get(peerEngineId);
            boolean authFailedStale = Objects.isNull(lastAuthFailed) || (this.clock.millis() - lastAuthFailed) >= OFFLINE_THRESHOLD_MS;

            if (authFailedStale) {
                // 403(인증거부)조차 최근에 못 받음 - "살아있는데 인증만 거부"가 아니라 진짜로 죽은 것으로 판단
                log.warn("[{}] AUTH_FAILED 상태에서 {}ms 이상 403 응답도 못 받음 - OFFLINE으로 전환", peerEngineId, OFFLINE_THRESHOLD_MS);
                this.knownEngines.put(peerEngineId, known.withPresenceStatus(PresenceStatus.OFFLINE));
                this.lastAuthFailedAt.remove(peerEngineId);

                return true;
            }

            return false;
        }

        // 처음 보는 피어에게는 유예를 줌 - 지금 막 발견됐다는 이유만으로 바로 OFFLINE 판정하지 않음
        this.lastPolledSuccessAt.putIfAbsent(peerEngineId, this.clock.millis());
        this.knownEngines.putIfAbsent(peerEngineId, new EngineInfo(
                peerEngineId,
                instance.getHost(),
                instance.getPort(),
                parsePriority(instance.getMetadata().get("engine-priority")),
                0L, // startedAt
                PresenceStatus.ONLINE, // 첫 발견 유예
                null // 폴링 실패라 engineRole을 아직 모름
        ));

        return false;
    }

    private boolean judgePresence() {
        long now = this.clock.millis();
        boolean changed = false;

        for (Map.Entry<String, Long> entry : this.lastPolledSuccessAt.entrySet()) {
            String peerEngineId = entry.getKey();
            EngineInfo current = this.knownEngines.get(peerEngineId);

            if (Objects.isNull(current) || current.presenceStatus() == PresenceStatus.AUTH_FAILED) {
                continue; // AUTH_FAILED는 인증 성공(pollOne 성공 분기)으로만 벗어남 - 타임아웃으로 함부로 안 바꿈
            }

            boolean online = (now - entry.getValue()) < OFFLINE_THRESHOLD_MS;
            PresenceStatus judged = online
                    ? PresenceStatus.ONLINE
                    : PresenceStatus.OFFLINE;

            if (current.presenceStatus() != judged) {
                if (judged == PresenceStatus.OFFLINE) {
                    log.warn("[{}] presenceStatus 변경: {} -> OFFLINE 판정 (마지막 성공: {}ms 전)", peerEngineId, current.presenceStatus(), now - entry.getValue());
                } else {
                    log.info("[{}] presenceStatus 변경: {} -> ONLINE 복귀", peerEngineId, current.presenceStatus());
                }

                this.knownEngines.put(peerEngineId, current.withPresenceStatus(judged));
                changed = true;
            }
        }

        return changed; // 알림을 직접 보내지 않고, "값이 바뀌었는지"만 불리언으로 리턴
    }

    private static int parsePriority(String value) {
        if (Objects.isNull(value)) {
            return Integer.MAX_VALUE;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[EngineDiscoveryService] engine-priority metadata 파싱 실패 (value = {}) - 최하위 우선순위로 처리", value, e);
            return Integer.MAX_VALUE;
        }
    }

    // 컨벤션 상 중첩 레코드 허용 (독립된 도메인 개념/상태 X, 여러 소유자가 공유 X, 다른 feature의 공개 계약 X, 중첩 때문에 흐름 방해 X)
    // 중첩 허용 기준 -> "한 클래스의 구현 세부 사항", "Owner.NestedType 관계가 의미 있음"
    private record PeerSelfInfo(
            String engineId,
            String host,
            int port,
            int priority,
            long startedAt,
            EngineRole engineRole
    ) {
    }
}
