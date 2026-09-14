package site.omagotchi.ruleservice.global.config;

import io.micrometer.common.KeyValues;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitListenerObservationConvention;
import org.springframework.amqp.rabbit.support.micrometer.RabbitTemplateObservationConvention;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Rabbit 발행·소비 계측의 공통 속성, 센서 식별자가 포함된 Routing Key 제외. */
@Configuration(proxyBeanMethods = false)
public class RabbitObservationConfig {

    @Bean
    public RabbitTemplateObservationConvention rabbitTemplateObservationConvention() {
        return new RabbitTemplateObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(RabbitMessageSenderContext context) {
                return KeyValues.of("messaging.system", "rabbitmq",
                        "messaging.operation.type", "send",
                        "messaging.destination.name", context.getExchange());
            }

            @Override
            public String getContextualName(RabbitMessageSenderContext context) {
                return "rabbitmq publish";
            }
        };
    }

    @Bean
    public RabbitListenerObservationConvention rabbitListenerObservationConvention() {
        return new RabbitListenerObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(RabbitMessageReceiverContext context) {
                String source = context.getSource();
                return KeyValues.of("messaging.system", "rabbitmq",
                        "messaging.operation.type", "process",
                        "messaging.destination.name", source == null ? "unknown" : source);
            }

            @Override
            public String getContextualName(RabbitMessageReceiverContext context) {
                return "rabbitmq consume";
            }
        };
    }
}
