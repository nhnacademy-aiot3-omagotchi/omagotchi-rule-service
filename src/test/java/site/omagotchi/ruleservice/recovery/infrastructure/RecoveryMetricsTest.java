package site.omagotchi.ruleservice.recovery.infrastructure;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryMetricsTest {

    private static final String PARKED_TOTAL = "rabbitmq.dead-letter";
    private static final String PARKED_DEPTH = "rabbitmq.parked";
    private static final String QUEUE_TAG = "raw.dead-letter";

    @Mock
    RabbitTemplate rabbitTemplate;

    @Mock
    Channel channel;

    @Mock
    AMQP.Queue.DeclareOk declareOk;

    SimpleMeterRegistry registry;
    RecoveryMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RecoveryMetrics(registry, rabbitTemplate);
    }

    @Test
    @DisplayName("카운터 태그는 예외 클래스명이고 종류마다 시계열이 갈린다")
    void countsByExceptionClassNameTest() {
        metrics.countedParked(new ConnectException());
        metrics.countedParked(new ConnectException());
        metrics.countedParked(new SocketTimeoutException());

        assertThat(registry.get(PARKED_TOTAL).tag("exception", "ConnectException").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(PARKED_TOTAL).tag("exception", "SocketTimeoutException").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("게이지는 조회할 때마다 파킹 큐 depth를 다시 읽는다")
    void depthGaugeIsPulledOnEachReadTest() throws IOException {
        // execute()는 채널을 빌려 콜백에 넘긴다. 목 채널을 대신 넣어 콜백 본문을 실행시킨다.
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<Double> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
        when(channel.queueDeclarePassive(RabbitTopologyConfig.QUEUE_RAW_DEAD_LETTER)).thenReturn(declareOk);
        when(declareOk.getMessageCount()).thenReturn(12, 3); // 첫 조회는 12건, 두 번째는 3건

        metrics.registerDepthGauge();
        Gauge gauge = registry.get(PARKED_DEPTH).tag("queue", QUEUE_TAG).gauge();

        // 저장된 값이 아니라 매번 브로커에 묻는다 — counter와의 근본 차이
        assertThat(gauge.value()).isEqualTo(12.0);
        assertThat(gauge.value()).isEqualTo(3.0);

        // 소비하지 않고 개수만 읽는다 (ADR-0024 규칙 4)
        verify(channel, times(2)).queueDeclarePassive(RabbitTopologyConfig.QUEUE_RAW_DEAD_LETTER);
    }

    @Test
    @DisplayName("브로커 조회가 실패하면 0을 돌려준다 - 연결 장애와 빈 큐가 구분되지 않는다")
    void depthGaugeReturnsZeroOnFailureTest() {
        when(rabbitTemplate.execute(any())).thenThrow(new RuntimeException("broker down"));
        metrics.registerDepthGauge();

        Gauge gauge = registry.get(PARKED_DEPTH).tag("queue", QUEUE_TAG).gauge();

        // 현재 동작을 못박아 둔다. NaN이 더 정직하다는 논의는 구현 가이드 참조
        assertThat(gauge.value()).isZero();
    }
}
