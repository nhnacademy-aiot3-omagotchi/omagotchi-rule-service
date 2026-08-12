package site.omagotchi.ruleservice.messaging.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;
import site.omagotchi.ruleservice.messaging.domain.PublishMode;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitPublisherNode;


import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishRetryBufferTest {

    @Mock
    RabbitTemplate rabbitTemplate;

    @Mock
    MeterRegistry registry;


    @Test
    @DisplayName("메세지 발송시 브로커에 문제 발생 - 버퍼 적재")
    void recoveryTest1(){
        PublishRetryBuffer buffer = new PublishRetryBuffer(rabbitTemplate, registry);
        RabbitPublisherNode node = new RabbitPublisherNode("pub-raw", rabbitTemplate, RabbitTopologyConfig.EXCHANGE_MAIN, PublishMode.RAW, buffer);

        doThrow(new RuntimeException("브로커 문제 발생"))
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class)
                );

        Message testMessage = Message.of("test-traceId", Map.of("sensorReading", sampleReading()));

        assertDoesNotThrow(() -> node.process(testMessage));
        assertEquals(1, buffer.getRawQSize());
    }

    @Test
    @DisplayName("메세지 발송시 브로커에 문제 발생 - 재발송")
    void recoveryTest2(){
        PublishRetryBuffer buffer = new PublishRetryBuffer(rabbitTemplate, registry); // 실제 버퍼

        buffer.offer(raw("raw.a"));
        buffer.offer(raw("raw.b"));
        buffer.offer(quality("quality.c"));


        buffer.stop();

        verify(rabbitTemplate, times(3)).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertEquals(0, buffer.getRawQSize());
        assertEquals(0, buffer.getQualityQSize());
    }

    @Test
    @DisplayName("상한 초과시 메세지 버림 테스트 - raw부터 폐기")
    void overflowEvictionTest(){
        PublishRetryBuffer buffer = new PublishRetryBuffer(rabbitTemplate, 2);

        buffer.offer(quality("qualitiy.message"));
        buffer.offer(raw("raw.old"));
        buffer.offer(raw("raw.new"));

        assertEquals(1, buffer.getDroppedCount());
    }



    private PendingMessage raw(String routingKey){
        return new PendingMessage(
                RabbitTopologyConfig.EXCHANGE_MAIN,
                routingKey,
                "test",
                "test-traceId",
                PublishMode.RAW
        );
    }

    private PendingMessage quality(String routingKey){
        return new PendingMessage(
                RabbitTopologyConfig.EXCHANGE_MAIN,
                routingKey,
                "test",
                "test-traceId",
                PublishMode.QUALITY
        );
    }
    private SensorReading sampleReading() {
        return new SensorReading(
                "trace-1",
                "livingroom",
                "point-a",
                "eui-123",
                "temperature",
                30.0,
                Instant.now(),
                Instant.now(),
                "온도센서",
                1L);
    }
}