package site.omagotchi.ruleservice.recovery.infrastructure;

import com.rabbitmq.client.GetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Slf4j
@RequiredArgsConstructor
@Component
public class MessageReplayer {

    private static final String QUEUE = RabbitTopologyConfig.QUEUE_RAW_DEAD_LETTER;
    private final RabbitTemplate rabbitTemplate;

    /**
     * DLQ 메세지 재발행
     * <p/>
     * recover가 남긴 x-original-exchange, x-original-routingKey를 검사 후 목적지 판별 <br/>
     * 해당 메세지로 재발행하고 재발행이 성공하면 ack응답을 보내 소비한걸로 처리한다. <br/>
     * 단, 이것마저 실패한 메세지는 다시 nack를 보내 DLQ에 적재한다.
     *
     * @param max 최대 몇건을 재발행할건지
     */
    public int replay(int max){
        // channel - Connection위에 띄워지는 논리적 연결 통로 고수준 a현pi를 지원함.
        return rabbitTemplate.execute(channel -> {
            List<Long> skipped = new ArrayList<>(); //재발행 마저 실패한 메세지 저장
            int count = 0;

            for(int i = 0; i < max; i++){
                GetResponse response = channel.basicGet(QUEUE, false);
                if(Objects.isNull(response)){
                    break;
                }

                long deliveryTag = response.getEnvelope().getDeliveryTag();
                Map<String, Object> headers = response.getProps().getHeaders();
                String exchange = header(headers, "x-original-exchange");

                if(Objects.isNull(exchange)){
                    skipped.add(deliveryTag);
                    log.warn("목적지 헤더 없는 메세지 검출. deliveryTag={}", deliveryTag);
                    continue;
                }

                String routingKey = header(headers, "x-original-routingKey");
                channel.basicPublish(
                        exchange,
                        Objects.isNull(routingKey) ? "" : routingKey,
                        response.getProps(),
                        response.getBody()
                );
                channel.basicAck(deliveryTag, false);
                count++;
            }

            // 실채한 메세지를 DLQ에 그냥 두도록 nack응답
            for (long tag : skipped) {
                channel.basicNack(tag, false, true);
            }

            return count;
        });
    }

    private String header(Map<String, Object> headers, String key){
        if(Objects.isNull(headers)){
            return null;
        }
        Object value = headers.get(key);
        return Objects.isNull(value) ? null : value.toString();
    }
}
