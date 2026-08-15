package site.omagotchi.ruleservice.inbound.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.quality.domain.RecordingConnection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MqttSubscriberNodeTest {

    private static final String TOPIC_FILTER = "application/#";
    private static final String CLIENT_ID = "rule-service-mqtt-sub";

    private MeterRegistry meterRegistry;
    private MqttSubscriberNode node;
    private RecordingConnection out;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        node = new MqttSubscriberNode(
                "mqtt-sub", "tcp://localhost:1883", CLIENT_ID, null, null, TOPIC_FILTER, meterRegistry);

        out = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.activate(); // 게이트 열기 - 아직 connect 전이라 브로커 없이도 동작
    }

    @Test
    @DisplayName("수신한 MQTT 메시지를 topic/raw/receivedAt 담은 Message로 out에 내보낸다")
    void publishesReceivedMessageToOut() throws Exception {
        String payload = "{\"value\":650.0,\"time\":\"2026-07-07T03:34:10.456Z\"}";
        Instant before = Instant.now();

        node.messageArrived("iot/실습실/전방우측/AM107/24e124128c140101/co2",
                new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));

        assertThat(out.messages()).hasSize(1);

        Message message = out.messages().getFirst();
        String topic = message.get("topic");
        String raw = message.get("raw");
        Instant receivedAt = message.get("receivedAt");

        assertThat(topic).isEqualTo("iot/실습실/전방우측/AM107/24e124128c140101/co2");
        assertThat(raw).isEqualTo(payload);
        // 수신 시각은 노드가 직접 찍으므로 호출 전후 구간 안에 있어야 한다
        assertThat(receivedAt).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("수신 메시지마다 새 traceId가 발급된다")
    void issuesNewTraceIdPerMessage() throws Exception {
        MqttMessage mqttMessage = new MqttMessage("{\"value\":650.0}".getBytes(StandardCharsets.UTF_8));

        node.messageArrived("iot/a/b/c/d/co2", mqttMessage);
        node.messageArrived("iot/a/b/c/d/co2", mqttMessage);

        assertThat(out.messages()).hasSize(2);

        String first = out.messages().getFirst().getTraceId();
        String second = out.messages().get(1).getTraceId();
        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("수신 건수 카운터가 topicFilter 태그와 함께 증가한다")
    void incrementsReceivedCounter() throws Exception {
        MqttMessage mqttMessage = new MqttMessage("{\"value\":650.0}".getBytes(StandardCharsets.UTF_8));

        node.messageArrived("iot/a/b/c/d/co2", mqttMessage);
        node.messageArrived("iot/a/b/c/d/co2", mqttMessage);

        double count = meterRegistry.get("mqtt.messages.received")
                .tag("topicFilter", TOPIC_FILTER)
                .counter()
                .count();
        assertThat(count).isEqualTo(2.0);
    }

    @Test
    @DisplayName("payload의 UTF-8 한글이 깨지지 않는다")
    void preservesUtf8Payload() throws Exception {
        String payload = "{\"value\":650.0,\"device_name\":\"실습실 센서\"}";

        node.messageArrived("iot/a/b/c/d/co2",
                new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));

        String raw = out.messages().getFirst().get("raw");
        assertThat(raw).contains("실습실 센서");
    }

    @Test
    @DisplayName("initialize 없이 shutdown해도 예외가 나지 않는다")
    void shutdownIsSafeWithoutInitialize() {
        // 기동 중 다른 노드가 실패해 flow 전체를 정리할 때, 연결 전 노드에도 shutdown이 호출된다
        assertThatCode(() -> node.shutdown()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onProcess는 빈 구현이라 입력을 받아도 아무것도 내보내지 않는다")
    void processDoesNothing() {
        node.process(Message.of(java.util.Map.of("topic", "iot/a/b/c/d/co2")));

        assertThat(out.messages()).isEmpty();
    }

    @Test
    @DisplayName("activate 전에는 콜백으로 메시지가 들어와도 out으로 내보내지 않는다")
    void dropsMessageWhenNotActivated() throws Exception {
        node.deactivate(); // setUp에서 열어둔 게이트를 닫음 (아직 connect 전이라 브로커 호출 없음)

        node.messageArrived("iot/test/co2", new MqttMessage("{\"value\":1}".getBytes(StandardCharsets.UTF_8)));

        assertThat(out.messages()).isEmpty();
    }

    @Test
    @DisplayName("비활성 상태에서 폐기한 메시지는 수신 건수 카운터에도 잡히지 않는다")
    void doesNotCountDroppedMsg() throws Exception {
        node.deactivate();

        node.messageArrived("iot/test/co2", new MqttMessage("{\"value\":1}".getBytes(StandardCharsets.UTF_8)));

        assertThat(meterRegistry.counter("mqtt.messages.received", "topicFilter", TOPIC_FILTER).count()).isZero();
    }

    @Test
    @DisplayName("shutdown 하면 게이트가 닫혀서, 재기동 후 역할 판정 전에 메시지를 처리하지 않는다")
    void shutdownClosesGate() throws Exception {
        node.shutdown(); // stop 상황 - 이후 start 되어도 게이트는 닫힌 채여야 함

        node.messageArrived("iot/test/co2", new MqttMessage("{\"value\":1}".getBytes(StandardCharsets.UTF_8)));

        assertThat(out.messages()).isEmpty();
    }
}
