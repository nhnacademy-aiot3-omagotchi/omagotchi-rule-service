package site.omagotchi.ruleservice.rule.infrastructure.messaging.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.inbound.SensorReading;
import site.omagotchi.ruleservice.quality.QualityEvent;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.PublishRetryBuffer;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.config.RabbitTopologyConfig;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RabbitPublisherNodeTest {

    private static final String EXCHANGE = RabbitTopologyConfig.EXCHANGE_MAIN;

    @Mock
    RabbitTemplate rabbitTemplate;

    @Mock
    PublishRetryBuffer retryBuffer;

    RabbitPublisherNode node;

    @BeforeEach
    void setUp() {
        node = new RabbitPublisherNode(
                "pub-raw",
                rabbitTemplate,
                EXCHANGE,
                PublishMode.RAW,
                retryBuffer
        );
    }

    @Test
    @DisplayName("raw모드 - raw.{location}.{measurement} 발행")
    void rawPublishTest() {
        SensorReading reading = sampleReading();
        Message message = Message.of("test-traceId", Map.of("sensorReading", reading));

        node.process(message);

        verify(rabbitTemplate).convertAndSend(
                eq(EXCHANGE),
                eq("raw.livingroom.temperature"),
                eq(reading),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        verifyNoInteractions(retryBuffer);
    }

    @Test
    @DisplayName("quality모드 - quality.{token}.{deviceEui} 발행")
    void qualityPublishTest() {
        RabbitPublisherNode qualityNode = new RabbitPublisherNode(
                "pub-quality",
                rabbitTemplate,
                EXCHANGE,
                PublishMode.QUALITY,
                retryBuffer
        );

        QualityEvent event = QualityEvent.from(sampleReading(), QualityEvent.Type.RULE_HIT, "임계 초과");
        Message message = Message.of("test-traceId", Map.of("qualityEvent", event));

        qualityNode.process(message);

        verify(rabbitTemplate).convertAndSend(
                eq(EXCHANGE),
                eq("quality.rulehit.eui-123"),
                eq(event),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    @Test
    @DisplayName("발행 중 예외 발생 - PublishRetryBuffer 적재")
    void publishFailTest() {
        Message message = Message.of("test-traceId", Map.of("sensorReading", sampleReading()));

        doThrow(new RuntimeException("브로커 다운"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        node.process(message);

        verify(retryBuffer).offer(any());
    }

    private SensorReading sampleReading() {
        return new SensorReading(
                "trace-1", "livingroom", "point-a", "eui-123",
                "temperature", 30.0, Instant.now(), Instant.now(), "온도센서");
    }
}
