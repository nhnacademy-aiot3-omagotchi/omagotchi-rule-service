package site.omagotchi.ruleservice.quality.domain;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;
import site.omagotchi.ruleservice.quality.infrastructure.QualityProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
public class MissingDetectorNode extends AbstractNode implements Activatable {

    private final LastSeenRegistry lastSeenRegistry;
    private final QualityProperties qualityProperties;

    private ScheduledExecutorService scheduledExecutorService;
    private ScheduledFuture<?> checkTask;
    private volatile Instant startedAt;

    private final Set<String> missingSensors = ConcurrentHashMap.newKeySet(); // 결측 센서 목록
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5); // 검사 주기
    private static final int MISSING_MULTIPLIER = 3; // 판정 배수
    private static final int DEFAULT_INTERVAL_SECONDS = 60; // 기본 센서 측정 주기

    public MissingDetectorNode(String id, LastSeenRegistry lastSeenRegistry, QualityProperties qualityProperties) {
        super(id);

        this.lastSeenRegistry = lastSeenRegistry;
        this.qualityProperties = qualityProperties;
        addOutputPort("missing");
    }

    /**
     * 스케줄러만 준비하고, 실제 주기 검사는 activate()에서 시작함
     * STANDBY 상태에서 이 타이머가 돌면 LastSeenRegistry가 비어있어(전환 시 이관 안 함) 잘못된 결측 판정이 날 수 있음
     */
    @Override
    public void initialize() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        super.initialize();
    }

    @Override
    public void shutdown() {
        if (Objects.nonNull(scheduledExecutorService)) {
            scheduledExecutorService.shutdownNow();
        }
        super.shutdown();
    }

    @Override
    protected void onProcess(Message message) {
        // 빈 구현
    }

    /**
     * activate() / deactivate():
     * 호출부 EngineRoleService.reevaluate()가 이미 synchronized라서, 이미 이미 호출하는 쪽에서 직렬화가 보장됨
     * 따라서 activate, deactivate에 synchronized가 없어도 동시에 두 스레드가 들어올 수 없기는 함
     * 그러나, 역할 전환 때만 가끔 호출되는 메서드라서 락을 걸어도 성능에 영향이 없고, 호출하는 쪽의 synchronized에만 의존하는 것이 불안하여 붙임
     * 테스트 코드에서 직접 여러 스레드에서 호출하거나, 다른 호출 경로가 추가되면 그때는 이 클래스 혼자서는 checkTask 필드를 지키지 못 함.
     * 만약 두 스레드가 동시에 activate()에 들어오면, scheduleAtFixedRate가 두 번 걸려서 검사가 중복으로 돌고, deactivate()가 그 중 하나만 취소하고 나머지는 절대 못 끄는 버그가 생길 수 있음
     */
    @Override
    public synchronized void activate() {
        if (Objects.nonNull(this.checkTask)) {
            return;
        }

        // 전환 직후 결측 판정 유예를 위해 활성화 시점을 기준으로 새로 잡음
        this.startedAt = Instant.now();
        this.missingSensors.clear();

        long interval = CHECK_INTERVAL.toSeconds();
        this.checkTask = this.scheduledExecutorService.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.SECONDS);
        log.info("[{}] 결측 감지 타이머 시작", getId());
    }

    @Override
    public synchronized void deactivate() {
        if (Objects.isNull(this.checkTask)) {
            return;
        }

        this.checkTask.cancel(false);
        this.checkTask = null;
        this.missingSensors.clear();
        log.info("[{}] 결측 감지 타이머 중단", getId());
    }

    /**
     * checkTask 필드는 volatile이 아니라서 synchronized 없이 읽으면
     * activate/deactivate를 호출한 다른 스레드(EngineRoleService의 스케줄러 스레드 등)가 방금 바꾼 값을
     * 이 스레드(FlowManager의 stop/start 호출 스레드 등)가 못 볼 수 있음
     * -> synchronized나 volatile 없이는 한 스레드의 쓰기가 다른 스레드에 언제 보이는지 보장 안 되므로.
     * -> activate/deactivate가 synchronized라서, isActivated도 같은 락을 잡아야 항상 최신값을 읽을 수 있음
     */
    @Override
    public synchronized boolean isActivated() {
        return Objects.nonNull(this.checkTask);
    }

    // 테스트에서 직접 호출하기 위해 package-private
    void check() {
        Instant now = Instant.now();

        for (QualityProperties.SensorId sensor : qualityProperties.inventory()) {

            String deviceEui = sensor.deviceEui();
            String measurement = sensor.measurement();
            Optional<Instant> lastSeen = lastSeenRegistry.lastSeenAt(deviceEui, measurement);
            boolean isMissing;
            int intervalSeconds = sensor.expectedIntervalSeconds() != null ? sensor.expectedIntervalSeconds() : DEFAULT_INTERVAL_SECONDS;
            Duration threshold = Duration.ofSeconds(intervalSeconds).multipliedBy(MISSING_MULTIPLIER);

            if (lastSeen.isEmpty() && Duration.between(startedAt, now).compareTo(threshold) < 0) {
                continue;
            }

            if (lastSeen.isEmpty()) {
                isMissing = true; // 한 번도 안 옴 → 결측
            } else {
                Duration sinceLastSeen = Duration.between(lastSeen.get(), now);
                isMissing = sinceLastSeen.compareTo(threshold) > 0; // 임계값 넘게 안 옴 → 결측
            }

            String key = deviceEui + ":" + measurement;
            boolean wasMissing = missingSensors.contains(key);

            if (isMissing && !wasMissing) {
                // 결측 시작
                missingSensors.add(key);
                log.info("[결측 시작] {}", key);
                QualityEvent qualityEvent = QualityEvent.missing(deviceEui, measurement, "결측 시작");
                send("missing", Message.of(Map.of("qualityEvent", qualityEvent)));
            } else if (!isMissing && wasMissing) {
                // 결측 종료
                missingSensors.remove(key);
                log.info("[결측 종료] {}", key);
                QualityEvent qualityEvent = QualityEvent.missing(deviceEui, measurement, "결측 종료");
                send("missing", Message.of(Map.of("qualityEvent", qualityEvent)));
            }
        }
    }
}
