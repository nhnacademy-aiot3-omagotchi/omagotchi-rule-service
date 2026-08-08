package site.omagotchi.ruleservice.messaging.infrastructure;

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
    public static final String EXCHANGE_MAIN = "omagotchi.sensor.main.exchange";
    public static final String EXCHANGE_RAW_DEAD_LETTER = "omagotchi.sensor.raw.dead-letter.exchange";

    public static final String QUEUE_RAW = "omagotchi.sensor.raw.queue";
    public static final String QUEUE_QUALITY = "omagotchi.sensor.quality.queue";
    public static final String QUEUE_RAW_DEAD_LETTER = "omagotchi.sensor.raw.dead-letter.queue";

    /** 파킹 큐 보존 정책. 첫 항의 L 없이 계산하면 int 오버플로로 음수가 된다. */
    private static final long TTL_30_DAYS_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MAX_LENGTH = 20_000L;

    //-------exchange------
    /** 센서, 품질 데이터 main exchange*/
    @Bean
    public TopicExchange exchangeMain(){
        return ExchangeBuilder
                .topicExchange(EXCHANGE_MAIN)
                .durable(true)
                .build();
    }

    /** 센서 데이터 dlx */
    @Bean
    public FanoutExchange exchangeRawDeadLetter(){
        return ExchangeBuilder
                .fanoutExchange(EXCHANGE_RAW_DEAD_LETTER)
                .durable(true)
                .build();
    }

    //--------queue--------
    // 메세지 저장소
    /** 일반 메세지 큐 */
    @Bean
    public Queue queueRaw(){
        return QueueBuilder
                .durable(QUEUE_RAW)
                .build();
    }

    /**
     * 품질 데이터 큐.
     *
     * deadLetterExchange를 의도적으로 두지 않는다. 이 큐의 소비자는 learning-service이고
     * 파킹 큐는 소비자가 소유한다. 이관 경로는 learning-service의 RepublishMessageRecoverer
     * 하나로 단일화한다 — 큐 속성과 recoverer가 각각 이관하면 헤더 구성이 다른 메시지가
     * 한 파킹 큐에 섞여 조사 시 혼란을 준다.
     * 위 queueRaw()에는 있으므로 누락으로 오인하지 말 것.
     */
    @Bean
    public Queue queueQuality(){
        return QueueBuilder
                .durable(QUEUE_QUALITY)
                .build();
    }

    /** 센서 데이터 dlq. ttl(int)은 30일을 담지 못해 인자로 직접 넣는다. */
    @Bean
    public Queue queueRawDeadLetter(){
        return QueueBuilder
                .durable(QUEUE_RAW_DEAD_LETTER)
                .withArgument("x-message-ttl", TTL_30_DAYS_MS)
                .maxLength(MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    //-------binding-------
    /** 센서 데이터 main-raw 바인딩 */
    @Bean
    public Binding rawBinding(){
        return BindingBuilder
                .bind(queueRaw())
                .to(exchangeMain())
                .with("raw.#");
    }

    /** 품질 데이터 main-quality 바인딩 */
    @Bean
    public Binding qualityBinding(){
        return BindingBuilder
                .bind(queueQuality())
                .to(exchangeMain())
                .with("quality.#");
    }

    /** 센서 데이터 dlx - dlq 바인딩 */
    @Bean
    public Binding bindingRawDeadLetter(){
        return BindingBuilder
                .bind(queueRawDeadLetter())
                .to(exchangeRawDeadLetter());
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter){
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);

        return rabbitTemplate;
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter(){
        return new JacksonJsonMessageConverter();
    }
}
