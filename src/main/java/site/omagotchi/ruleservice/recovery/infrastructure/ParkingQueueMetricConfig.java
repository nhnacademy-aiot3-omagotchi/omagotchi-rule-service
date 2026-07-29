package site.omagotchi.ruleservice.recovery.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.micrometer.core.instrument.Gauge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.config.RabbitTopologyConfig;

@Configuration
public class ParkingQueueMetricConfig {

    @Bean
    public MeterBinder parkingQueueDepth(RabbitTemplate rabbitTemplate){
        return registry -> {
            registerDepthGauge(rabbitTemplate, registry, "dead-letter", RabbitTopologyConfig.QUEUE_DEAD_LETTER);
            registerDepthGauge(rabbitTemplate, registry, "unrouted", RabbitTopologyConfig.QUEUE_UNROUTED);
        };
    }

    private void registerDepthGauge(RabbitTemplate rabbitTemplate, MeterRegistry registry, String tag, String queue){
        Gauge.builder("rabbitmq.parked", () -> queueDepth(rabbitTemplate, queue))
                .tag("queue", tag)
                .register(registry);
    }

    private double queueDepth(RabbitTemplate rabbitTemplate, String queue){
        try{
            return rabbitTemplate.execute(channel ->
                    (double) channel.queueDeclarePassive(queue).getMessageCount());
        }catch (Exception e){
            return 0.0;
        }
    }
}
