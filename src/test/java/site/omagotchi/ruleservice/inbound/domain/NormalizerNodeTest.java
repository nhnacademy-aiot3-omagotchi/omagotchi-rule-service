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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerNodeTest {

    private NormalizerNode node;
    private LastSeenRegistry lastSeenRegistry;
    private RecordingConnection out;
    private RecordingConnection invalid;

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-31T03:37:30.000Z");
    private static final Instant MIN_NS_TIME = Instant.parse("2026-07-31T03:37:26.818201Z");
    private static final String EUI = "24e124136d151836";

    private static final String VALID_FRAME = """
            {
              "deduplicationId": "4012a5ad-9d97-4d99-9dc6-8ca11ba6a7bb",
              "time": "2026-07-31T03:41:30.305+00:00",
              "deviceInfo": {
                "deviceProfileName": "AM103",
                "deviceName": "실습실-am103",
                "devEui": "24e124136d151836",
                "tags": { "location": "실습실", "point": "후방" }
              },
              "fCnt": 94794,
              "fPort": 85,
              "object": { "temperature": 26.4, "humidity": 62.5, "co2": 550, "battery": 76 },
              "rxInfo": [
                { "gatewayId": "24e124fffef5dccc", "nsTime": "2026-07-31T03:37:26.822898+00:00" },
                { "gatewayId": "24e124fffef79304", "nsTime": "2026-07-31T03:37:26.818201+00:00" }
              ]
            }""";

    @BeforeEach
    void setUp() {
        lastSeenRegistry = new LastSeenRegistry();
        node = new NormalizerNode("normalizer", lastSeenRegistry);
        out = new RecordingConnection();
        invalid = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("invalid").connect(invalid);
    }

    //mqttSubscriberNode 역할
    private Message input(String raw) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("topic", "application/96b4d719/device/" + EUI + "/event/up");
        payload.put("raw", raw);
        payload.put("receivedAt", RECEIVED_AT);
        return Message.of(payload);
    }

    private List<SensorReading> readings() {
        return out.messages().stream()
                .map(m -> m.<SensorReading>get("sensorReading"))
                .toList();
    }

    // object 내용만 바꿔 끼우는 프레임 빌더 (도어 센서 등)
    private String frameWithObject(String objectJson) {
        return """
                {
                  "deviceInfo": {
                    "deviceProfileName": "WS301",
                    "deviceName": "출입문-ws301",
                    "devEui": "24e124141e180806",
                    "tags": { "location": "사무실", "point": "출입문" }
                  },
                  "fCnt": 19668,
                  "object": %s,
                  "rxInfo": [ { "gatewayId": "24e124fffef79304", "nsTime": "2026-07-31T03:37:26.818201+00:00" } ]
                }""".formatted(objectJson);
    }

    //분해
    @Test
    @DisplayName("정상 프레임은 object 항목 수만큼 SensorReading으로 분해된다")
    void explodesFrameIntoReadings() {
        node.process(input(VALID_FRAME));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(4);

        Map<String, Double> values = new HashMap<>();
        for (SensorReading r : readings()) {
            values.put(r.measurement(), r.value());
        }
        assertThat(values)
                .containsEntry("temperature", 26.4)
                .containsEntry("humidity", 62.5)
                .containsEntry("co2", 550.0)
                .containsEntry("battery", 76.0);

        SensorReading first = readings().get(0);
        assertThat(first.location()).isEqualTo("실습실");
        assertThat(first.point()).isEqualTo("후방");
        assertThat(first.deviceEui()).isEqualTo(EUI);
        assertThat(first.deviceName()).isEqualTo("실습실-am103");
        assertThat(first.fCnt()).isEqualTo(94794L);
    }

    @Test
    @DisplayName("분해된 모든 SensorReading이 입력 메시지의 traceId를 승계한다")
    void allReadingsInheritTraceId() {
        Message input = input(VALID_FRAME);
        node.process(input);

        for (Message m : out.messages()) {
            assertThat(m.getTraceId()).isEqualTo(input.getTraceId());
            assertThat(m.<SensorReading>get("sensorReading").traceId()).isEqualTo(input.getTraceId());
        }
    }

    //시각
    @Test
    @DisplayName("measuredAt은 최상위 time(게이트웨이 시계)이 아니라 rxInfo nsTime의 최솟값이다")
    void usesMinNsTimeNotTopLevelTime() {
        node.process(input(VALID_FRAME));

        SensorReading reading = readings().get(0);
        // 최상위 time(03:41:30, +243초 게이트웨이)이었다면 이 검증은 실패한다
        assertThat(reading.measuredAt()).isEqualTo(MIN_NS_TIME);

        boolean substituted = out.messages().get(0).get("_timeSubstituted");
        assertThat(substituted).isFalse();
    }

    @Test
    @DisplayName("rxInfo가 없으면 receivedAt으로 대체하고 _timeSubstituted를 true로 표시한다")
    void substitutesReceivedAtWhenRxInfoMissing() {
        String raw = """
                {
                  "deviceInfo": { "devEui": "24e124136d151836", "tags": { "location": "실습실" } },
                  "fCnt": 1,
                  "object": { "temperature": 26.4 }
                }""";

        node.process(input(raw));

        assertThat(invalid.messages()).isEmpty();
        assertThat(readings().get(0).measuredAt()).isEqualTo(RECEIVED_AT);

        boolean substituted = out.messages().get(0).get("_timeSubstituted");
        assertThat(substituted).isTrue();
    }

    @Test
    @DisplayName("nsTime 형식이 파싱되지 않으면 invalid로 보낸다")
    void rejectsUnparsableNsTime() {
        String raw = """
                {
                  "deviceInfo": { "devEui": "24e124136d151836", "tags": { "location": "실습실" } },
                  "fCnt": 1,
                  "object": { "temperature": 26.4 },
                  "rxInfo": [ { "nsTime": "not-a-time" } ]
                }""";

        node.process(input(raw));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("payload 파싱 실패");
    }

    //태그
    @Test
    @DisplayName("tags에 point가 없으면(회의실) point는 null이다")
    void allowsMissingPoint() {
        String raw = """
                {
                  "deviceInfo": {
                    "deviceProfileName": "AM103",
                    "deviceName": "회의실-am103",
                    "devEui": "24e124725d089152",
                    "tags": { "location": "회의실" }
                  },
                  "fCnt": 60249,
                  "object": { "temperature": 25.1 },
                  "rxInfo": [ { "nsTime": "2026-07-31T03:37:26.818201+00:00" } ]
                }""";

        node.process(input(raw));

        assertThat(invalid.messages()).isEmpty();
        SensorReading reading = readings().get(0);
        assertThat(reading.location()).isEqualTo("회의실");
        assertThat(reading.point()).isNull();
    }

    //값 변환
    @Test
    @DisplayName("magnet_status open은 door 1.0으로 변환된다")
    void convertsMagnetOpenToDoor() {
        node.process(input(frameWithObject("""
                { "magnet_status": "open", "battery": 92 }""")));

        assertThat(out.messages()).hasSize(2);

        Map<String, Double> values = new HashMap<>();
        for (SensorReading r : readings()) {
            values.put(r.measurement(), r.value());
        }
        assertThat(values).containsEntry("door", 1.0).containsEntry("battery", 92.0);
    }

    @Test
    @DisplayName("magnet_status close는 door 0.0으로 변환된다")
    void convertsMagnetCloseToDoor() {
        node.process(input(frameWithObject("""
                { "magnet_status": "close" }""")));

        assertThat(readings().get(0).measurement()).isEqualTo("door");
        assertThat(readings().get(0).value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("boolean 값은 1.0/0.0으로 변환된다")
    void convertsBooleanValue() {
        node.process(input(frameWithObject("""
                { "occupied": true }""")));

        assertThat(readings().get(0).measurement()).isEqualTo("occupied");
        assertThat(readings().get(0).value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("숫자화할 수 없는 문자열 항목은 스킵하고 나머지는 발행한다")
    void skipsNonNumericValue() {
        node.process(input(frameWithObject("""
                { "temperature": 26.4, "tamper_status": "normal" }""")));

        assertThat(invalid.messages()).isEmpty();
        assertThat(out.messages()).hasSize(1);
        assertThat(readings().get(0).measurement()).isEqualTo("temperature");
    }

    @Test
    @DisplayName("fCnt가 없으면 null로 조립된다")
    void allowsMissingFcnt() {
        String raw = """
                {
                  "deviceInfo": { "devEui": "24e124136d151836", "tags": { "location": "실습실" } },
                  "object": { "temperature": 26.4 },
                  "rxInfo": [ { "nsTime": "2026-07-31T03:37:26.818201+00:00" } ]
                }""";

        node.process(input(raw));

        assertThat(readings().get(0).fCnt()).isNull();
    }

    //무효
    @Test
    @DisplayName("deviceInfo가 없으면 invalid로 보낸다")
    void rejectsMissingDeviceInfo() {
        node.process(input("""
                { "object": { "temperature": 26.4 } }"""));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.type()).isEqualTo(QualityEvent.Type.INVALID);
        assertThat(event.detail()).contains("deviceInfo/object 누락");
    }

    @Test
    @DisplayName("object가 없으면 invalid로 보낸다")
    void rejectsMissingObject() {
        node.process(input("""
                { "deviceInfo": { "devEui": "24e124136d151836" } }"""));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("deviceInfo/object 누락");
    }

    @Test
    @DisplayName("devEui가 없으면 invalid로 보낸다")
    void rejectsMissingDevEui() {
        node.process(input("""
                {
                  "deviceInfo": { "deviceName": "이름만있음" },
                  "object": { "temperature": 26.4 }
                }"""));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("devEui 누락");
    }

    @Test
    @DisplayName("JSON 형식이 깨졌으면 invalid로 보낸다")
    void rejectsMalformedJson() {
        node.process(input("{\"deviceInfo\":"));

        assertThat(out.messages()).isEmpty();
        assertThat(invalid.messages()).hasSize(1);

        QualityEvent event = invalid.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).contains("payload 파싱 실패");
    }

    @Test
    @DisplayName("측정항목마다 LastSeenRegistry가 갱신된다")
    void updatesLastSeenPerMeasurement() {
        node.process(input(VALID_FRAME));

        assertThat(lastSeenRegistry.lastSeenAt(EUI, "temperature")).contains(RECEIVED_AT);
        assertThat(lastSeenRegistry.lastSeenAt(EUI, "co2")).contains(RECEIVED_AT);
        assertThat(lastSeenRegistry.lastSeenAt(EUI, "humidity")).contains(RECEIVED_AT);
        assertThat(lastSeenRegistry.lastSeenAt(EUI, "battery")).contains(RECEIVED_AT);
    }
}