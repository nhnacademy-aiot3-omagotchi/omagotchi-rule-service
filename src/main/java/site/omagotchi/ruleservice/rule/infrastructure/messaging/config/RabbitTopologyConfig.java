package site.omagotchi.ruleservice.rule.infrastructure.messaging.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitTopologyConfig {
    public static final String EXCHANGE_MAIN = "sensor.main.exchange";
    public static final String EXCHANGE_DEAD_LETTER = "sensor.dead-letter.exchange";
    public static final String EXCHANGE_UNROUTED = "sensor.unrouted.exchange";

    public static final String QUEUE_RAW = "sensor.raw.queue";
    public static final String QUEUE_QUALITY = "sensor.quality.queue";
    public static final String QUEUE_UNROUTED= "sensor.unrouted.queue";
    public static final String QUEUE_DEAD_LETTER = "sensor.dead-letter.queue";

    //-------exchange------
    // 메세지 분배 역할
    /** TopicExchange를 통해 와일드 카드 토픽에 따라 분기하도록 설정*/
    @Bean
    public TopicExchange topicExchange(){
        return ExchangeBuilder
                .topicExchange(EXCHANGE_MAIN)
                .alternate(EXCHANGE_UNROUTED)
                .durable(true)
                .build();
    }

    /** 라우팅 실패 exchange. */
    @Bean
    public FanoutExchange unroutedExchange(){
        return ExchangeBuilder
                .fanoutExchange(EXCHANGE_UNROUTED)
                .durable(true)
                .build();
    }

    /** 처리실패 메세지 exchange.*/
    @Bean
    public FanoutExchange deadLetterExchange(){
        return ExchangeBuilder
                .fanoutExchange(EXCHANGE_DEAD_LETTER)
                .durable(true)
                .build();
    }
    //---------------------

    //--------queue--------
    // 메세지 저장소
    /** 큐A - 일반 메세지 큐*/
    @Bean
    public Queue queueRaw(){
        return QueueBuilder
                .durable(QUEUE_RAW)
                .deadLetterExchange(EXCHANGE_DEAD_LETTER)
                .build();
    }

    /** 큐 B - 품질 메세지 큐*/
    @Bean
    public Queue queueQuality(){
        return QueueBuilder
                .durable(QUEUE_QUALITY)
                .deadLetterExchange(EXCHANGE_DEAD_LETTER)
                .build();
    }
    /** 라우팅 실패 메시지 큐*/
    @Bean
    public Queue unroutedQueue(){
        return QueueBuilder
                .durable(QUEUE_UNROUTED)
                .build();
    }

    /** dlq - 처리 실패 메세지 큐*/
    @Bean
    public Queue deadLetterQueue(){
        return QueueBuilder
                .durable(QUEUE_DEAD_LETTER)
                .build();
    }
    //---------------------

    //-------binding-------
    // routingKey에 따라 exchange에서 어떤 queue에 적재할 것인가
    /** 일반 메세지 바인딩 */
    @Bean
    public Binding rawBinding(){
        return BindingBuilder
                .bind(queueRaw())
                .to(topicExchange())
                .with("raw.#");
    }

    /** 품질 메세지 바인딩 */
    @Bean
    public Binding qualityBinding(){
        return BindingBuilder
                .bind(queueQuality())
                .to(topicExchange())
                .with("quality.#");
    }
    /** 라우팅 실패 바인딩 */
    @Bean
    public Binding unroutedBinding(){
        return BindingBuilder
                .bind(unroutedQueue())
                .to(unroutedExchange());
    }

    /** 처리실패 메세지 바인딩*/
    @Bean
    public Binding deadLetterBinding(){
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(deadLetterExchange());
    }
    //---------------------

    //TODO: nack의 경우 재발행 시도 콜백 등록
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter){
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            log.warn("발행 nack. 버퍼에 적재하겠습니다. cause:{}", cause);
        });
        return rabbitTemplate;
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter(){
        return new JacksonJsonMessageConverter();
    }
}
