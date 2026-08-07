package site.omagotchi.ruleservice.recovery.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

/** 메세지 회복에 관한 메트릭 수집 */
@Component
@RequiredArgsConstructor
public class RecoveryMetrics {
    private static final String PARKED_TOTAL = "rabbitmq.dead-letter";
    private static final String PARKED_DEPTH = "rabbitmq.parked";

    private static final String QUEUE_TAG = "raw.dead-letter";

    private final MeterRegistry registry;
    private final RabbitTemplate rabbitTemplate;

    /**게이지 매번 RabbitMQ브로커를 통해 큐에 몇건이 적재되어있는지 확인*/
    @PostConstruct // 생성자에 의존성 주입이 끝난후 호출되는 콜백 함수
    void registerDepthGauge(){
        Gauge.builder(PARKED_DEPTH, this::depth)
                .tag("queue", QUEUE_TAG)
                .register(registry);
    }

    /** 카운터 예외를 태그로 누적 몇건의 데이터가 DLQ로 들어갔는지 체크 */
    public void countedParked(Throwable rootCause){
        registry.counter(PARKED_TOTAL, "exception", rootCause.getClass().getSimpleName()).increment();
    }

    private double depth(){
        try{
            return rabbitTemplate.execute(channel ->
                    (double) channel.queueDeclarePassive(RabbitTopologyConfig.QUEUE_RAW_DEAD_LETTER).getMessageCount());
        }catch (Exception e){
            return 0.0;
        }
    }
}