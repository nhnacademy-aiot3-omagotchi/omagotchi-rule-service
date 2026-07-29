package site.omagotchi.ruleservice.inbound.domain;

import site.omagotchi.ruleservice.inbound.domain.NormalizerNode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.application.FlowEngine;
import site.omagotchi.ruleservice.flow.domain.Flow;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.CollectorNode;
import site.omagotchi.ruleservice.quality.domain.LastSeenRegistry;

/**
 * MQTT 브로커 없이 normalizer -> collector 파이프라인을 실제 FlowEngine 위에서 돌려,
 * 원시 메시지가 SensorReading으로 정규화되고 traceId가 전 구간 승계되는지 검증한다. (C-4 축소판)
 */
class InboundPipelineTest {

    private static final String FLOW_ID = "test-inbound-pipeline";

    private final FlowEngine engine = new FlowEngine();

    @AfterEach
    void tearDown() {
        // 워커 스레드 누수 방지 - 검증 실패로 stop을 못 만나도 반드시 정지
        try {
            engine.stop(FLOW_ID);
        } catch (Exception ignored) {
            // 이미 정지됐거나 등록 안 된 경우 무시
        }
    }

    @Test
    @DisplayName("normalizer -> collector 파이프라인을 통과한 뒤에도 traceId가 최초 입력과 동일하다")
    void traceIdPropagatesThroughNormalizeAndCollect() throws InterruptedException {
        // given: normalizer -> collector 배선 후 엔진 시작
        LastSeenRegistry lastSeenRegistry = new LastSeenRegistry();
        NormalizerNode normalizer = new NormalizerNode("normalizer",lastSeenRegistry);
        CollectorNode collector = new CollectorNode("collector");

        Flow flow = new Flow(FLOW_ID);
        flow.addNode(normalizer);
        flow.addNode(collector);
        flow.connect("normalizer", "out", "collector", "in");

        engine.register(flow);
        engine.start(FLOW_ID);

        // MqttSubscriberNode가 만들었을 법한 원시 메시지 (traceId는 여기서 발급됨)
        String rawJson = "{\"value\":650.0,"
                + "\"time\":\"2026-07-07T03:34:10.456Z\","
                + "\"device_name\":\"AM107-140101\","
                + "\"device_eui\":\"24e124128c140101\"}";
        Message input = Message.of(Map.of(
                "topic", "iot/실습실/전방우측/AM107/24e124128c140101/co2",
                "raw", rawJson,
                "receivedAt", Instant.now()
        ));

        // when: normalizer 입구로 원시 메시지를 밀어넣는다 (MQTT 수신 콜백 대역)
        normalizer.getInputPort("in").receive(input);

        // then: 워커 스레드가 collector로 전달할 때까지 최대 2초 대기
        List<Message> collected = awaitCollected(collector, 1, 2000);

        assertThat(collected).hasSize(1);

        Message result = collected.get(0);
        // 핵심: 정규화를 거쳐도 traceId가 최초 입력과 같아야 한다 (추적 승계)
        assertThat(result.getTraceId()).isEqualTo(input.getTraceId());

        SensorReading reading = result.get("sensorReading");
        assertThat(reading).isNotNull();
        assertThat(reading.traceId()).isEqualTo(input.getTraceId());
        assertThat(reading.location()).isEqualTo("실습실");
        assertThat(reading.measurement()).isEqualTo("co2");
        assertThat(reading.value()).isEqualTo(650.0);
        assertThat(reading.deviceEui()).isEqualTo("24e124128c140101");
    }

    /**
     * collector가 expectedSize개를 모을 때까지 timeoutMillis 동안 짧게 폴링하며 기다린다.
     * FlowEngine이 연결마다 별도 소비 스레드로 처리하므로 도착이 비동기라 대기가 필요하다.
     */
    private List<Message> awaitCollected(CollectorNode collector, int expectedSize, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline
                && collector.getCollected().size() < expectedSize) {
            Thread.sleep(20);
        }
        return collector.getCollected();
    }
}
