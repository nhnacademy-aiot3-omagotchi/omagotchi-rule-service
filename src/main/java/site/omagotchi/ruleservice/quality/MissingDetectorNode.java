package site.omagotchi.ruleservice.quality;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MissingDetectorNode extends AbstractNode {

    private final LastSeenRegistry lastSeenRegistry;
    private final QualityProperties qualityProperties;

    private ScheduledExecutorService scheduledExecutorService;
    private Instant startedAt;

    private final Set<String> missingSensors = ConcurrentHashMap.newKeySet();   //결측 센서 목록
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5);      //검사 주기
    private static final int MISSING_MULTIPLIER = 3;                            //판정 배수
    private static final int DEFAULT_INTERVAL_SECONDS = 60;                     //기본 센서 측정 주기

    public MissingDetectorNode(String id, LastSeenRegistry lastSeenRegistry, QualityProperties qualityProperties) {
        super(id);
        this.lastSeenRegistry = lastSeenRegistry;
        this.qualityProperties = qualityProperties;
        addOutputPort("missing");
    }

    @Override
    public void initialize() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        startedAt = Instant.now();

        long interval = CHECK_INTERVAL.toSeconds();
        scheduledExecutorService.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.SECONDS);
        super.initialize();
    }

    @Override
    public void shutdown() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }
        super.shutdown();
    }

    @Override
    protected void onProcess(Message message) {

    }

    void check() {                                  //테스트에서 직접 호출하기 위해 package-private
        Instant now = Instant.now();

        for (QualityProperties.SensorId sensor : qualityProperties.inventory()) {

            String deviceEui = sensor.deviceEui();
            String measurement = sensor.measurement();
            Optional<Instant> lastSeen = lastSeenRegistry.lastSeenAt(deviceEui, measurement);
            boolean isMissing;
            int intervalSeconds = sensor.expectedIntervalSeconds() != null ? sensor.expectedIntervalSeconds() : DEFAULT_INTERVAL_SECONDS;
            Duration threshold = Duration.ofSeconds(intervalSeconds).multipliedBy(MISSING_MULTIPLIER);

            if (lastSeen.isEmpty() && Duration.between(startedAt,now).compareTo(threshold) < 0){
                continue;
            }

            if (lastSeen.isEmpty()) {
                isMissing = true;   // 한 번도 안 옴 → 결측
            } else {
                Duration sinceLastSeen = Duration.between(lastSeen.get(), now);
                isMissing = sinceLastSeen.compareTo(threshold) > 0;   // 임계값 넘게 안 옴 → 결측
            }

            String key = deviceEui + ":" + measurement;
            boolean wasMissing = missingSensors.contains(key);

            if (isMissing && !wasMissing) {
                //결측 시작
                missingSensors.add(key);
                log.info("[결측 시작] {}", key);
                QualityEvent qualityEvent = QualityEvent.missing(deviceEui,measurement,"결측 시작");
                send("missing",Message.of(Map.of("qualityEvent", qualityEvent)));
            } else if (!isMissing && wasMissing) {
                //결측 종료
                missingSensors.remove(key);
                log.info("[결측 종료] {}", key);
                QualityEvent qualityEvent = QualityEvent.missing(deviceEui,measurement,"결측 종료");
                send("missing",Message.of(Map.of("qualityEvent", qualityEvent)));
            }
        }
    }
}
