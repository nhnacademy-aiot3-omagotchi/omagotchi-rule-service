package site.omagotchi.ruleservice.recovery.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;
import site.omagotchi.ruleservice.recovery.application.RawFailureTracker;

import java.util.Objects;

@Slf4j
@Configuration
public class RabbitRecoverConfig {

    /**
     * 재시도 소진뒤 메시지를 DLQ로 이관.
     * RepublishMessageRecover가 예외 정보를 x-exception-*, 원래 메세지의 정보를 x-original-*에 실어 발행함
     */
    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate, RecoveryMetrics metrics, RawFailureTracker tracker){
        RepublishMessageRecoverer delegate = new RepublishMessageRecoverer(rabbitTemplate, RabbitTopologyConfig.EXCHANGE_RAW_DEAD_LETTER);

        return (message, cause) -> {

            // @RabbitListener 에서 발생한 예외는 Spring AMQP가 ListenerExecutionFailedException으로 감싸서 넘김
            // getMostSpecificCause()를 통해서 원래 원인이 최상위 예외를 뽑아냄
            Throwable root = NestedExceptionUtils.getMostSpecificCause(cause);
            metrics.countedParked(root);

            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            if(!Objects.isNull(routingKey) && routingKey.startsWith("raw.")){
                tracker.onParked(root);
            }

            //실제로 DLQ로 이관함. 이때 헤더를 추가해줌
            //x-exception-stacktrace, x-exception-message, x-original-exchange, x-original-routingKey
            delegate.recover(message, cause);
        };
    }
}
