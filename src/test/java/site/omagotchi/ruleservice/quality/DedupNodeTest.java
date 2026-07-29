package site.omagotchi.ruleservice.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.inbound.SensorReading;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DedupNodeTest {

    private DedupNode node;
    private RecordingConnection out;
    private RecordingConnection duplicate;
    private RecordingConnection delayed;

    @BeforeEach
    void setUp() {
        node = new DedupNode("dedup");
        out = new RecordingConnection();
        duplicate = new RecordingConnection();
        delayed = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("duplicate").connect(duplicate);
        node.getOutputPort("delayed").connect(delayed);
    }

    private Message message(double value, Instant measuredAt, Instant receivedAt) {
        SensorReading reading = new SensorReading(
                "trace-1", "실습실", "전방", "eui-1", "co2",
                value, measuredAt, receivedAt, "sensor-1");
        return Message.of("trace-1", Map.of("sensorReading", reading));
    }

    @Test
    @DisplayName("처음 보는 데이터는 통과하고 중복 신고가 없다")
    void firstTimePassesTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message(650.0, base, base));

        assertThat(out.messages()).hasSize(1);
        assertThat(duplicate.messages()).isEmpty();
        assertThat(delayed.messages()).isEmpty();
    }

    @Test
    @DisplayName("같은 데이터가 두 번 오면 통과는 1건이고 중복 신고 1건이 발행된다")
    void duplicateBlockedTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message(650.0, base, base));
        node.process(message(650.0, base, base));

        assertThat(out.messages()).hasSize(1);
        assertThat(duplicate.messages()).hasSize(1);

        QualityEvent qualityEvent = duplicate.messages().get(0).get("qualityEvent");
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.DUPLICATE);
    }

    @Test
    @DisplayName("61초 늦게 도착한 데이터는 지연 표시를 달고 통과하며 신고도 발행한다")
    void delayedMarksAndReportsTest() {
        Instant measuredAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant receivedAt = measuredAt.plusSeconds(61);

        node.process(message(650.0, measuredAt, receivedAt));

        assertThat(out.messages()).hasSize(1);
        assertThat((Boolean) out.messages().get(0).get("_delayed")).isTrue();

        QualityEvent qualityEvent = delayed.messages().get(0).get("qualityEvent");
        assertThat(delayed.messages()).hasSize(1);
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.DELAYED);
        assertThat(qualityEvent.detail()).isEqualTo("지연: 61초");
    }

    @Test
    @DisplayName("60초 이하 지연은 신고하지 않는다")
    void exactly60SecondsIsNotDelayedTest() {
        Instant measuredAt = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message(650.0, measuredAt, measuredAt.plusSeconds(60)));

        assertThat(out.messages()).hasSize(1);
        assertThat(delayed.messages()).isEmpty();
        assertThat((Boolean) out.messages().get(0).get("_delayed")).isNull();
    }
}
