package site.omagotchi.ruleservice.recovery.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;
import site.omagotchi.ruleservice.recovery.application.RawFailureTracker;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RabbitRecoverConfigTest {

    private static final String RAW_ROUTING_KEY = "raw.class_a.temperature";
    private static final String RULE_ROUTING_KEY = "rule.updated";

    @Mock
    RabbitTemplate rabbitTemplate;

    @Mock
    RecoveryMetrics metrics;

    @Mock
    RawFailureTracker tracker;

    MessageRecoverer recoverer;

    @BeforeEach
    void setUp() {
        recoverer = new RabbitRecoverConfig().messageRecoverer(rabbitTemplate, metrics, tracker);
    }

    @Test
    @DisplayName("raw 경로 실패는 카운터와 상태 전이 양쪽에 반영된다")
    void rawFailureTracksBothTest() {
        recoverer.recover(messageWith(RAW_ROUTING_KEY), wrapped(new ConnectException("Connection refused")));

        verify(metrics).countedParked(any(ConnectException.class));
        verify(tracker).onParked(any(ConnectException.class));
    }

    @Test
    @DisplayName("raw 외 경로 실패는 카운터만 올리고 raw 장애 판정을 건드리지 않는다")
    void nonRawFailureSkipsTrackerTest() {
        recoverer.recover(messageWith(RULE_ROUTING_KEY), wrapped(new IllegalStateException("이상 룰")));

        verify(metrics).countedParked(any(IllegalStateException.class));
        verifyNoInteractions(tracker); // rule.updated 실패가 "raw 적재 실패 시작"을 찍으면 안 된다
    }

    @Test
    @DisplayName("여러 겹 감싼 예외에서도 가장 안쪽 원인이 넘어간다")
    void unwrapsToRootCauseTest() {
        ConnectException root = new ConnectException("Connection refused");
        Throwable cause = wrapped(new IOException("influx write failed", root));

        recoverer.recover(messageWith(RAW_ROUTING_KEY), cause);

        // 언랩하지 않으면 ListenerExecutionFailedException이, 한 겹만 벗기면 IOException이 넘어간다
        verify(metrics).countedParked(any(ConnectException.class));
    }

    @Test
    @DisplayName("raw 파킹 큐의 DLX로 원 목적지 헤더를 실어 재발행한다")
    void republishesToRawDeadLetterExchangeTest() {
        Message message = messageWith(RAW_ROUTING_KEY);

        recoverer.recover(message, wrapped(new ConnectException()));

        verify(rabbitTemplate).send(eq(RabbitTopologyConfig.EXCHANGE_RAW_DEAD_LETTER), anyString(), any(Message.class));

        // recoverer가 이 메세지에 헤더를 직접 심는다. MessageReplayer가 그 헤더를 읽어 원 목적지를 되찾는다.
        assertThat(message.getMessageProperties().getHeaders())
                .containsEntry("x-original-exchange", RabbitTopologyConfig.EXCHANGE_MAIN)
                .containsEntry("x-original-routingKey", RAW_ROUTING_KEY)
                .containsKey("x-exception-message")
                .containsKey("x-exception-stacktrace");
    }

    private Message messageWith(String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange(RabbitTopologyConfig.EXCHANGE_MAIN);
        properties.setReceivedRoutingKey(routingKey);

        return new Message("{\"value\":25.5}".getBytes(StandardCharsets.UTF_8), properties);
    }

    /** 리스너에서 나온 예외는 항상 이 래퍼에 감싸여 온다 — 언랩을 검증하려면 실제로 감싸야 한다. */
    private Throwable wrapped(Throwable cause) {
        return new ListenerExecutionFailedException("Listener method threw exception", cause);
    }
}
