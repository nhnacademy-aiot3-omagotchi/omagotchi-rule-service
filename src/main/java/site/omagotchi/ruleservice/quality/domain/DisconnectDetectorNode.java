package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.quality.infrastructure.QualityProperties;

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
public class DisconnectDetectorNode extends AbstractNode implements Activatable {

    private static final String DETAIL_START = "끊김 시작";
    private static final String DETAIL_END = "끊김 종료";

    private final LastSeenRegistry lastSeenRegistry;
    private final QualityProperties qualityProperties;

    private ScheduledExecutorService scheduledExecutorService;
    private ScheduledFuture<?> checkTask;
    private volatile Instant startedAt;

    private final Set<String> disconnectKeys = ConcurrentHashMap.newKeySet();   //결측 센서 목록
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5);      //검사 주기
    private static final int DISCONNECT_MULTIPLIER = 3;                            //판정 배수
    private static final int DEFAULT_INTERVAL_SECONDS = 60;                     //기본 센서 측정 주기

    public DisconnectDetectorNode(String id, LastSeenRegistry lastSeenRegistry, QualityProperties qualityProperties) {
        super(id);
        this.lastSeenRegistry = lastSeenRegistry;
        this.qualityProperties = qualityProperties;
        addOutputPort("disconnect");
    }

    /**
     * 스케줄러만 준비하고, 실제 주기 검사는 activate()에서 시작함
     * STANDBY 상태에서 이 타이머가 돌면 LastSeenRegistry가 비어있어(전환 시 이관 안 함) 잘못된 결측 판정이 날 수 있음
     */
    @Override
    public void initialize() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        startedAt = Instant.now();
        super.initialize();
    }

    @Override
    public void shutdown() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
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
        this.disconnectKeys.clear();

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
        this.disconnectKeys.clear();
        log.info("[{}] 결측 감지 타이머 중단", getId());
    }

    // 테스트에서 직접 호출하기 위해 package-private
    void check() {
        Instant now = Instant.now();

        for (QualityProperties.SensorId sensor : qualityProperties.inventory()) {

            String deviceEui = sensor.deviceEui();
            String measurement = sensor.measurement();
            Optional<Instant> lastSeen = lastSeenRegistry.lastSeenAt(deviceEui, measurement);
            boolean isDisconnect;
            int intervalSeconds = sensor.expectedIntervalSeconds() != null ? sensor.expectedIntervalSeconds() : DEFAULT_INTERVAL_SECONDS;
            Duration threshold = Duration.ofSeconds(intervalSeconds).multipliedBy(DISCONNECT_MULTIPLIER);

            if (lastSeen.isEmpty() && Duration.between(startedAt, now).compareTo(threshold) < 0) {
                continue;
            }

            if (lastSeen.isEmpty()) {
                isDisconnect = true;   // 한 번도 안 옴 → 결측
            } else {
                Duration sinceLastSeen = Duration.between(lastSeen.get(), now);
                isDisconnect = sinceLastSeen.compareTo(threshold) > 0;   // 임계값 넘게 안 옴 → 결측
            }

            String key = key(deviceEui, measurement);
            boolean wasDisconnect = disconnectKeys.contains(key);

            if (isDisconnect && !wasDisconnect) {
                disconnectKeys.add(key);
                log.info("[{}] {}", DETAIL_START, key);
                QualityEvent qualityEvent = QualityEvent.disconnected(deviceEui, measurement, DETAIL_START);
                send("disconnect", Message.of(Map.of("qualityEvent", qualityEvent)));

            } else if (!isDisconnect && wasDisconnect) {
                disconnectKeys.remove(key);
                log.info("[{}] {}", DETAIL_END, key);
                QualityEvent qualityEvent = QualityEvent.disconnected(deviceEui, measurement, DETAIL_END);
                send("disconnect", Message.of(Map.of("qualityEvent", qualityEvent)));
            }
        }
    }

    private static String key(String deviceEui, String measurement) {
        return deviceEui + ":" + measurement;
    }
}
