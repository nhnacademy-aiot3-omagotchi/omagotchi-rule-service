package site.omagotchi.ruleservice.recovery.infrastructure;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReplayerTest {

    private static final String QUEUE = RabbitTopologyConfig.QUEUE_RAW_DEAD_LETTER;
    private static final String ORIGINAL_EXCHANGE = RabbitTopologyConfig.EXCHANGE_MAIN;
    private static final String ORIGINAL_ROUTING_KEY = "raw.class_a.temperature";

    @Mock
    RabbitTemplate rabbitTemplate;

    @Mock
    Channel channel;

    MessageReplayer replayer;

    @BeforeEach
    void setUp() {
        // execute()는 채널을 빌려 콜백에 넘긴다. 목 채널을 대신 넣어 콜백 본문을 실행시킨다.
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });

        replayer = new MessageReplayer(rabbitTemplate);
    }

    @Test
    @DisplayName("DLQ에 들어간 raw데이터 재발행 성공 테스트")
    void publishSuccessTest() throws Exception {
        when(channel.basicGet(QUEUE, false))
                .thenReturn(responseWith(1L, Map.of(
                        "x-original-exchange", ORIGINAL_EXCHANGE,
                        "x-original-routingKey", ORIGINAL_ROUTING_KEY)))
                .thenReturn(null); // 큐가 비면 루프 종료

        int replayed = replayer.replay(10);

        assertThat(replayed).isEqualTo(1);
        verify(channel).basicPublish(eq(ORIGINAL_EXCHANGE), eq(ORIGINAL_ROUTING_KEY), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("DLQ에 들어간 raw데이터 재발행 실패 테스트")
    void publishFailTest() throws Exception {
        when(channel.basicGet(QUEUE, false))
                .thenReturn(responseWith(1L, Map.of("x-death", "브로커가 직접 데드레터링한 흔적")))
                .thenReturn(null);

        int replayed = replayer.replay(10);

        assertThat(replayed).isZero();
        verify(channel, never()).basicPublish(any(), any(), any(), any());
        verify(channel).basicNack(1L, false, true); // requeue=true — 유실 없이 큐에 되돌린다
    }

    @Test
    @DisplayName("건너뛴 메세지가 루프를 막지 않아 뒤 메세지까지 처리된다")
    void skippedMessageDoesNotBlockLoopTest() throws Exception {
        when(channel.basicGet(QUEUE, false))
                .thenReturn(responseWith(1L, Map.of("x-death", "목적지 없음")))
                .thenReturn(responseWith(2L, Map.of(
                        "x-original-exchange", ORIGINAL_EXCHANGE,
                        "x-original-routingKey", ORIGINAL_ROUTING_KEY)))
                .thenReturn(null);

        int replayed = replayer.replay(10);

        // 1번을 즉시 requeue했다면 다음 basicGet이 1번을 또 꺼내 2번에 닿지 못한다
        assertThat(replayed).isEqualTo(1);
        verify(channel).basicAck(2L, false);
        verify(channel).basicNack(1L, false, true);
    }

    @Test
    @DisplayName("max에 도달하면 큐에 메세지가 남아 있어도 멈춘다")
    void stopsAtMaxTest() throws Exception {
        when(channel.basicGet(QUEUE, false))
                .thenReturn(responseWith(1L, Map.of(
                        "x-original-exchange", ORIGINAL_EXCHANGE,
                        "x-original-routingKey", ORIGINAL_ROUTING_KEY))); // 계속 메세지가 있는 상태

        int replayed = replayer.replay(3);

        assertThat(replayed).isEqualTo(3);
        verify(channel, times(3)).basicGet(QUEUE, false);
    }

    @Test
    @DisplayName("큐가 비어 있으면 아무것도 하지 않는다")
    void emptyQueueTest() throws Exception {
        when(channel.basicGet(QUEUE, false)).thenReturn(null);

        int replayed = replayer.replay(10);

        assertThat(replayed).isZero();
        verify(channel, never()).basicPublish(any(), any(), any(), any());
    }

    private GetResponse responseWith(long deliveryTag, Map<String, Object> headers) {
        // envelope의 키에는 error. 접두사가 붙어 있다 — 헤더를 읽어야 한다는 계약을 픽스처가 드러낸다
        Envelope envelope = new Envelope(deliveryTag, false, ORIGINAL_EXCHANGE, "error." + ORIGINAL_ROUTING_KEY);
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .build();

        return new GetResponse(envelope, props, "{\"value\":25.5}".getBytes(StandardCharsets.UTF_8), 0);
    }
}
