package site.omagotchi.ruleservice.inbound.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.quality.domain.LastSeenRegistry;
import site.omagotchi.ruleservice.quality.domain.QualityEvent;
import site.omagotchi.ruleservice.quality.domain.RecordingConnection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerNodeTest {

    private NormalizerNode node;
    private LastSeenRegistry lastSeenRegistry;
    private RecordingConnection out;
    private RecordingConnection invalid;

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-07T03:34:11.000Z");
    private static final Instant MEASURED_AT = Instant.parse("2026-07-07T03:34:10.456Z");
    private static final String EUI = "24e124128c140101";
    private static final String IOT_TOPIC_6 = "iot/실습실/전방우측/AM107/" + EUI + "/co2";

    private static final String VALID_RAW = """
            {"value":650.0,"time":"2026-07-07T03:34:10.456Z","device_name":"AM107-140101"}""";

    @BeforeEach
    void setUp() {
        lastSeenRegistry = new LastSeenRegistry();
        node = new NormalizerNode("normalizer", lastSeenRegistry);
        out = new RecordingConnection();
        invalid = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("invalid").connect(invalid);
    }

    //토픽 파싱
    private Message input(String topic, String raw){
        Map<String,Object> payload = new HashMap<>();
        payload.put("topic", topic);
        payload.put("raw", raw);
        payload.put("receivedAt", RECEIVED_AT);
        return Message.of(payload);
    }

    @Test
    @DisplayName("iot 6세그먼트 토픽은 location/point/eui/measurement로 분해된다")
    void parsesFullIotTopic() {
        node.process(input(IOT_TOPIC_6, VALID_RAW));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(1);

        SensorReading reading = out.messages().get(0).get("sensorReading");
        assertThat(reading.location()).isEqualTo("실습실");
        assertThat(reading.point()).isEqualTo("전방우측");
        assertThat(reading.deviceEui()).isEqualTo(EUI);
        assertThat(reading.measurement()).isEqualTo("co2");
        assertThat(reading.value()).isEqualTo(650.0);
    }

    @Test
    @DisplayName("iot 5세그먼트 토픽은 point가 null이고 eui는 네 번째 세그먼트다")
    void parsesIotTopicWithoutPoint() {
        node.process(input("iot/실습실/AM107/" + EUI + "/co2", VALID_RAW));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(1);

        SensorReading reading = out.messages().get(0).get("sensorReading");
        assertThat(reading.location()).isEqualTo("실습실");
        assertThat(reading.point()).isNull();
        assertThat(reading.deviceEui()).isEqualTo(EUI);
        assertThat(reading.measurement()).isEqualTo("co2");
    }

    @Test
    @DisplayName("iot 토픽 세그먼트가 4개면 invalid로 보낸다")
    void rejectsIotTopicWithTooFewSegments() {
        node.process(input("iot/실습실/AM107/co2", VALID_RAW));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.type()).isEqualTo(QualityEvent.Type.INVALID);
        assertThat(event.detail()).contains("세그먼트 수 비정상");
    }

    @Test
    @DisplayName("iot 토픽 세그먼트가 7개면 invalid로 보낸다")
    void rejectsIotTopicWithTooManySegments() {
        node.process(input(IOT_TOPIC_6 + "/extra", VALID_RAW));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.type()).isEqualTo(QualityEvent.Type.INVALID);
        assertThat(event.detail()).contains("세그먼트 수 비정상");
    }

    @Test
    @DisplayName("modbus 토픽은 location=modbus, point=gateway, eui=modbus로 고정된다")
    void parsesModbusTopic() {
        node.process(input("modbus/temperature", VALID_RAW));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(1);

        SensorReading reading = out.messages().get(0).get("sensorReading");
        assertThat(reading.location()).isEqualTo("modbus");
        assertThat(reading.point()).isEqualTo("gateway");
        assertThat(reading.deviceEui()).isEqualTo("modbus");
        assertThat(reading.measurement()).isEqualTo("temperature");
    }

    @Test
    @DisplayName("measurement가 없는 modbus 토픽은 invalid로 보낸다")
    void rejectsModbusTopicWithoutMeasurement() {
        node.process(input("modbus", VALID_RAW));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("세그먼트 부족");
    }

    @Test
    @DisplayName("iot/modbus가 아닌 접두사는 invalid로 보낸다")
    void rejectsUnknownTopicPrefix() {
        node.process(input("foo/bar/baz", VALID_RAW));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("알 수 없는 토픽 형식");
    }

    //페이로드 파싱
    @Test
    @DisplayName("value가 없으면 invalid로 보낸다")
    void rejectsMissingValue() {
        String raw = """
                    {"time":"2026-07-07T03:34:10.456Z","device_name":"AM107-140101"}""";

        node.process(input(IOT_TOPIC_6, raw));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.type()).isEqualTo(QualityEvent.Type.INVALID);
        assertThat(event.detail()).contains("value: 누락");
    }

    @Test
    @DisplayName("JSON 형식이 깨졌으면 invalid로 보낸다")
    void rejectsMalformedJson() {
        node.process(input(IOT_TOPIC_6, "{\"value\":650.0"));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("payload 파싱 실패");
    }

    @Test
    @DisplayName("time이 없으면 receivedAt으로 대체하고 _timeSubstituted를 true로 표시한다")
    void substitutesReceivedAtWhenTimeMissing() {
        String raw = """
                    {"value":650.0,"device_name":"AM107-140101"}""";

        node.process(input(IOT_TOPIC_6, raw));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(1);

        SensorReading reading = out.messages().get(0).get("sensorReading");
        assertThat(reading.measuredAt()).isEqualTo(RECEIVED_AT);
        assertThat(reading.receivedAt()).isEqualTo(RECEIVED_AT);

        boolean substituted = out.messages().get(0).get("_timeSubstituted");
        assertThat(substituted).isTrue();
    }

    @Test
    @DisplayName("time 대신 timestamp 키로 와도 측정 시각으로 인식한다")
    void acceptsTimestampKeyAsAlias() {
        String raw = """
                    {"value":650.0,"timestamp":"2026-07-07T03:34:10.456Z","device_name":"AM107-140101"}""";

        node.process(input(IOT_TOPIC_6, raw));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(1);

        SensorReading reading = out.messages().get(0).get("sensorReading");
        assertThat(reading.measuredAt()).isEqualTo(MEASURED_AT);

        boolean substituted = out.messages().get(0).get("_timeSubstituted");
        assertThat(substituted).isFalse();
    }

    @Test
    @DisplayName("time 형식이 Instant로 파싱되지 않으면 invalid로 보낸다")
    void rejectsUnparsableTime() {
        String raw = """
                    {"value":650.0,"time":"not-a-time","device_name":"AM107-140101"}""";

        node.process(input(IOT_TOPIC_6, raw));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("payload 파싱 실패");
    }

    @Test
    @DisplayName("device_name이 없으면 deviceName은 null이다")
    void allowsMissingDeviceName() {
        String raw = """
                    {"value":650.0,"time":"2026-07-07T03:34:10.456Z"}""";

        node.process(input(IOT_TOPIC_6, raw));

        assertThat(out.messages()).hasSize(1);

        SensorReading reading = out.messages().get(0).get("sensorReading");
        assertThat(reading.deviceName()).isNull();
    }
}
