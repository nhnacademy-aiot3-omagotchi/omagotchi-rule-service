package site.omagotchi.ruleservice.distributed.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    //    private static final long OFFLINE_THRESHOLD_MS = 12_000L;
    private static final long OFFLINE_THRESHOLD_MS = 4_000L; // 12초 -> 4초로 변경 (테스트) (폴링 1초 기준 약 3번 연속 실패 필요)

    private final DiscoveryClient discoveryClient;
    private final RestClient engineInternalRestClient;
    private final String applicationName;
    private final String selfEngineId;
    private final List<EnginePresenceListener> enginePresenceListeners;
    private final Clock clock;

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

    //    @Scheduled(fixedDelay = 3, timeUnit = TimeUnit.SECONDS)
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS) // 폴링 간격 3초 -> 1초 (테스트)
    public void pollPeers() {
        boolean discoveredNewPeer = false;

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

                this.pollOne(peerEngineId, instance);
            }
        } catch (Exception e) {
            log.warn("Eureka 피어 목록 조회 실패 - 이번 주기는 건너뜁니다", e);
        }

        boolean statusChanged = this.judgePresence();

        if (discoveredNewPeer || statusChanged) {
            this.enginePresenceListeners.forEach(EnginePresenceListener::onPresenceChanged);
        }
    }

    private void pollOne(String peerEngineId, ServiceInstance instance) {
        try {
            PeerSelfInfo response = this.engineInternalRestClient.get()
                    .uri("http://{host}:{port}/api/v1/internal/engines/self", instance.getHost(), instance.getPort())
                    .retrieve()
                    .body(PeerSelfInfo.class);

            PresenceStatus previousStatus = this.resolvePreviousStatus(peerEngineId);

            this.knownEngines.put(peerEngineId, new EngineInfo(
                    response.engineId(),
                    instance.getHost(),
                    instance.getPort(),
                    response.priority(),
                    response.startedAt(),
                    previousStatus, // presenceStatus 판정·로깅은 judgePresence()가 전달,
                    response.engineRole() // 폴링 성공 시엔 피어가 방금 보고한 engineRole 그대로 반영
            ));

            this.lastPolledSuccessAt.put(peerEngineId, this.clock.millis());
        } catch (Exception e) {
            log.debug("[{}] 폴링 실패 (host = {}, port = {})", peerEngineId, instance.getHost(), instance.getPort(), e);

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
    }

    private PresenceStatus resolvePreviousStatus(String peerEngineId) {
        EngineInfo existing = this.knownEngines.get(peerEngineId);

        return Objects.nonNull(existing)
                ? existing.presenceStatus()
                : PresenceStatus.ONLINE; // 첫 발견 유예
    }

    private boolean judgePresence() {
        long now = this.clock.millis();
        boolean changed = false;

        for (Map.Entry<String, Long> entry : this.lastPolledSuccessAt.entrySet()) {
            String peerEngineId = entry.getKey();
            boolean online = (now - entry.getValue()) < OFFLINE_THRESHOLD_MS;
            PresenceStatus judged = online
                    ? PresenceStatus.ONLINE
                    : PresenceStatus.OFFLINE;

            EngineInfo current = this.knownEngines.get(peerEngineId);

            // current가 널이 아니고, 판정이 달라졌으면 갱신
            if (Objects.nonNull(current) && current.presenceStatus() != judged) {
                if (judged == PresenceStatus.OFFLINE) {
                    log.warn("[{}] presenceStatus 변경: {} -> OFFLINE 판정 (마지막 성공: {}ms 전)",
                            peerEngineId, current.presenceStatus(), now - entry.getValue());
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
        return Objects.isNull(value)
                ? Integer.MAX_VALUE
                : Integer.parseInt(value);
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
