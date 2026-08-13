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
 * Eureka는 주소 해결에만 사용하고, 생존 판정은 이 폴링 결과로만 함(Eureka의 lease/eviction 미사용)
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineDiscoveryService implements EngineDirectoryPort {

    private static final long OFFLINE_THRESHOLD_MS = 3_000L;

    private final DiscoveryClient discoveryClient;
    private final RestClient engineInternalRestClient;
    private final String applicationName;
    private final String selfEngineId;
    private final List<EnginePresenceListener> enginePresenceListeners;
    private final Clock clock;
    private final Set<String> authFailureWarned = ConcurrentHashMap.newKeySet(); // 동시성 문제 X

    // peerEngineId -> 현재 알려진 정보(판정된 presenceStatus 포함)
    private final Map<String, EngineInfo> knownEngines = new ConcurrentHashMap<>();

    // peerEngineId -> 마지막으로 폴링에 성공한 시각(첫 발견 시점엔 유예를 위해 지금 시각으로 시드)
    private final Map<String, Long> lastPolledSuccessAt = new ConcurrentHashMap<>();

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
     * applicationName("rule-service")으로 Eureka에서 인스턴스 목록을 가져와서,
     * 각 인스턴스의 metadata-map(engine-id, engine-priority)을 읽어 EngineInfo로 변환하고,
     * engine.id가 자기 자신과 같은 건 걸러냄
     */
    @Override
    public List<EngineInfo> listEngines() {
        return List.copyOf(this.knownEngines.values());
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    public void pollPeers() {
        boolean discoveredNewPeer = false;
        boolean peerRoleChanged = false;
        Set<String> polledThisCycle = new HashSet<>();

        try {
            for (ServiceInstance instance : this.discoveryClient.getInstances(this.applicationName)) {
                String peerEngineId = instance.getMetadata().get("engine-id");

                if (Objects.isNull(peerEngineId) || peerEngineId.equals(this.selfEngineId)) {
                    continue;
                }

                // "새 피어 발견"을 별도로 감지해서 항상 알림
                if (!this.knownEngines.containsKey(peerEngineId)) {
                    discoveredNewPeer = true; // 처음 보는 피어 - 상태와 무관하게 존재 자체를 알려야 함
                }

                if (this.pollOne(peerEngineId, instance)) {
                    peerRoleChanged = true;
                }

                polledThisCycle.add(peerEngineId);
            }
        } catch (Exception e) {
            log.warn("Eureka 피어 목록 조회 실패 - 이번 주기는 건너뜁니다", e);
        }

        // Eureka가 이번 주기에 못 돌려준(예: discovery-service 재배포로 registry가 잠깐 비는 상황) 피어도 이미 알고 있는 주소로 직접 폴링을 이어감
        // Eureka 장애 자체가 승격 트리거가 되지 않도록
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
                peerRoleChanged = true;
            }
        }

        boolean statusChanged = this.judgePresence();

        if (discoveredNewPeer || statusChanged || peerRoleChanged) {
            this.enginePresenceListeners.forEach(EnginePresenceListener::onPresenceChanged);
        }
    }

    private boolean pollOne(String peerEngineId, ServiceInstance instance) {
        try {
            PeerSelfInfo response = this.engineInternalRestClient.get()
                    .uri("http://{host}:{port}/api/v1/internal/engines/self", instance.getHost(), instance.getPort())
                    .retrieve()
                    .body(PeerSelfInfo.class);

            this.authFailureWarned.remove(peerEngineId); // 복구되면 다음 실패 때 다시 경고할 수 있도록 초기화

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

            // 피어의 role 변화도 presenceStatus 전이와 동일하게 리스너에게 알려야 함
            // (안 그러면 failback 대기 중인 엔진이 상대가 실제로 강등되는 순간을 영영 못 보고 재판정 기회를 잃음)
            return previousRole != response.engineRole;

        } catch (HttpClientErrorException.Forbidden e) {
            // 상대가 살아서 응답은 했지만 인증을 거부함 - "죽었다"는 증거가 아니라 설정 오류일 가능성이 높음
            // 타임아웃 기반 OFFLINE 판정 경로를 안 타고, 즉시 AUTH_FAILED로 확정
            if (this.authFailureWarned.add(peerEngineId)) { // 이 피어에 대해 처음 경고하는 거면 true일 것임
                log.warn("[{}] 폴링 인증 실패 (host = {}, port = {}) - INTERNAL_SHARED_SECRET이 양쪽 엔진에 동일하게 설정됐는지 확인하세요", peerEngineId, instance.getHost(), instance.getPort());
            }

            // 이미 아는 피어면 priority/startedAt/engineRole은 그대로 두고 상태만 바꿈
            // (폴백 폴링 인스턴스는 metadata가 비어 있어서 새로 파싱하면 priority가 유실되고,
            // 그러면 상위 우선순위 피어가 최하위로 둔갑해서 AUTH_FAILED 승격 보류 로직이 무력화됨)
            this.knownEngines.compute(peerEngineId, (k, known) -> Objects.nonNull(known)
                    ? known.withPresenceStatus(PresenceStatus.AUTH_FAILED)
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
            return false;

        } catch (Exception e) {
            log.debug("[{}] 폴링 실패 (host = {}, port = {})", peerEngineId, instance.getHost(), instance.getPort(), e);
            this.markFailure(peerEngineId, instance);

            return false;
        }
    }

    private void markFailure(String peerEngineId, ServiceInstance instance) {
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
