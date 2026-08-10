package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.quality.domain.QualityEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class StuckSensorNodeTest {

    private StuckSensorNode node;
    private RecordingConnection out;
    private RecordingConnection stuck;

    @BeforeEach
    void setUp() {
        node = new StuckSensorNode("stuck");
        out = new RecordingConnection();
        stuck = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("stuck").connect(stuck);
    }

    private Message message(String measurement, double value, Instant measuredAt) {
        SensorReading reading = new SensorReading(
                "trace-1", "실습실", "전방", "eui-1", measurement,
                value, measuredAt, measuredAt, "sensor-1",1L);
        return Message.of("trace-1", Map.of("sensorReading", reading));
    }

    @Test
    @DisplayName("처음 보는 센서는 통과만 시킨다")
    void firstReadingPassesTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("temperature", 30.5, base));

        assertThat(out.messages()).hasSize(1);
        assertThat(stuck.messages()).isEmpty();
    }

    @Test
    @DisplayName("30분 이내 무변동은 신고하지 않는다")
    void within30MinutesNoAlertTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("temperature", 30.5, base));
        node.process(message("temperature", 30.5, base.plus(29, ChronoUnit.MINUTES)));

        assertThat(out.messages()).hasSize(2);
        assertThat(stuck.messages()).isEmpty();
    }

    @Test
    @DisplayName("30분을 초과한 무변동은 신고를 발행한다")
    void over30MinutesAlertsTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("temperature", 30.5, base));
        node.process(message("temperature", 30.5, base.plus(31, ChronoUnit.MINUTES)));

        assertThat(out.messages()).hasSize(2);
        assertThat(stuck.messages()).hasSize(1);

        QualityEvent qualityEvent = stuck.messages().get(0).get("qualityEvent");
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.STUCK);
        assertThat(qualityEvent.detail()).isEqualTo("무변동: 31분");
    }

    @Test
    @DisplayName("신고 후 6시간 이내에는 다시 신고하지 않는다")
    void noReAlertWithin6HoursTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("temperature", 30.5, base));
        node.process(message("temperature", 30.5, base.plus(31, ChronoUnit.MINUTES)));
        node.process(message("temperature", 30.5, base.plus(35, ChronoUnit.MINUTES)));
        node.process(message("temperature", 30.5, base.plus(5, ChronoUnit.HOURS)));

        assertThat(stuck.messages()).hasSize(1);
    }

    @Test
    @DisplayName("마지막 신고 후 6시간이 지나면 다시 신고한다")
    void reAlertAfter6HoursTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("temperature", 30.5, base));
        node.process(message("temperature", 30.5, base.plus(31, ChronoUnit.MINUTES)));
        node.process(message("temperature", 30.5, base.plus(7, ChronoUnit.HOURS)));

        assertThat(stuck.messages()).hasSize(2);
    }

    @Test
    @DisplayName("값이 바뀌면 무변동 상태가 초기화된다")
    void valueChangeResetsTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("temperature", 30.5, base));
        node.process(message("temperature", 30.6, base.plus(31, ChronoUnit.MINUTES)));
        node.process(message("temperature", 30.6, base.plus(45, ChronoUnit.MINUTES)));

        assertThat(stuck.messages()).isEmpty();
    }

    @Test
    @DisplayName("문 센서는 값이 안 변해도 신고하지 않는다")
    void doorSensorNeverStuckTest() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");

        node.process(message("door", 0.0, base));
        node.process(message("door", 0.0, base.plus(10, ChronoUnit.HOURS)));

        assertThat(out.messages()).hasSize(2);
        assertThat(stuck.messages()).isEmpty();
    }
}
