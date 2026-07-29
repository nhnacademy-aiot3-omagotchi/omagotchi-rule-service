package site.omagotchi.ruleservice.recovery.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.config.RabbitTopologyConfig;

/**
 * 재시도 3회 후 <code>dlx -> dlq</code>로 보내는 과정. <br/>
 * 원인에 대한 헤더를 추가해서 메세지를 보낸다.
 */
@Configuration
public class RabbitRecoverConfig {

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry){

        Counter dlqCounter = meterRegistry.counter("rabbitmq.dead-letter");

        RepublishMessageRecoverer delegate = new RepublishMessageRecoverer(rabbitTemplate, RabbitTopologyConfig.EXCHANGE_DEAD_LETTER);

        return ((message, cause) -> {
            dlqCounter.increment();
            delegate.recover(message, cause);
        });
    }
}
