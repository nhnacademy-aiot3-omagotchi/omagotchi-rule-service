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
    private final Set<String> missingSensors = ConcurrentHashMap.newKeySet();   //결측 센서 목록
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);      //검사 주기
    private static final int MISSING_MULTIPLIER = 3;                            //판정 배수

    public MissingDetectorNode(String id, LastSeenRegistry lastSeenRegistry, QualityProperties qualityProperties) {
        super(id);
        this.lastSeenRegistry = lastSeenRegistry;
        this.qualityProperties = qualityProperties;
        addOutputPort("missing");
    }

    @Override
    public void initialize() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        long interval = CHECK_INTERVAL.toSeconds();
        long initialDelay = interval * MISSING_MULTIPLIER;
        scheduledExecutorService.scheduleAtFixedRate(this::check, initialDelay, interval, TimeUnit.SECONDS);
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

    private void check() {
        Instant now = Instant.now();
        Duration threshold = CHECK_INTERVAL.multipliedBy(MISSING_MULTIPLIER);

        for (QualityProperties.SensorId sensor : qualityProperties.inventory()) {

            String deviceEui = sensor.deviceEui();
            String measurement = sensor.measurement();
            Optional<Instant> lastSeen = lastSeenRegistry.lastSeenAt(deviceEui, measurement);
            boolean isMissing;

            if (lastSeen.isEmpty()) {
                isMissing = true;   // 한 번도 안 옴 → 결측
            } else {
                Duration sinceLastSeen = Duration.between(lastSeen.get(), now);
                isMissing = sinceLastSeen.compareTo(threshold) > 0;   // 90초 넘게 안 옴 → 결측
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
