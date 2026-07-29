package site.omagotchi.ruleservice.recovery.infrastructure;

import site.omagotchi.ruleservice.recovery.application.port.MessageReplayer;

import com.rabbitmq.client.GetResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.recovery.domain.ParkingQueue;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import java.util.Map;
import java.util.Objects;

/**
 * basicGet(autoAck=false)으로 한 건 꺼내되, 재발행 성공 후에만 ack한다 —
 * 발행 실패 시 파킹 큐에서 사라지는 유실을 막기 위함. 목적지를 못 찾은 메시지는 nack(requeue)로 보존한다.
 */
@Component
@RequiredArgsConstructor
public class RabbitMessageReplayer implements MessageReplayer {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public int replay(ParkingQueue queue, int max) {
        return rabbitTemplate.execute(channel -> {
            int count = 0;
            for (int i = 0; i < max; i++) {
                GetResponse response = channel.basicGet(sourceQueue(queue), false); // autoAck=false
                if (response == null) {
                    break; // 큐가 비면 종료
                }

                long deliveryTag = response.getEnvelope().getDeliveryTag();

                Destination destination = resolveDestination(queue, response);
                if (destination.exchange() == null) {
                    channel.basicNack(deliveryTag, false, true); // 목적지 못 찾음 → 보존
                    continue;
                }

                channel.basicPublish(
                        destination.exchange(),
                        destination.routingKey() == null ? "" : destination.routingKey(),
                        response.getProps(),
                        response.getBody()); // 원 목적지로 재발행
                channel.basicAck(deliveryTag, false); // 발행 성공 후에만 확정
                count++;
            }
            return count;
        });
    }

    private String sourceQueue(ParkingQueue queue) {
        return switch (queue) {
            case DEAD_LETTER -> RabbitTopologyConfig.QUEUE_DEAD_LETTER;
            case UNROUTED -> RabbitTopologyConfig.QUEUE_UNROUTED;
        };
    }

    private Destination resolveDestination(ParkingQueue queue, GetResponse response) {
        return switch (queue) {
            // DLQ: RepublishMessageRecoverer가 남긴 원 목적지 헤더로 되돌림
            case DEAD_LETTER -> {
                Map<String, Object> headers = response.getProps().getHeaders();
                yield new Destination(
                        header(headers, "x-original-exchange"),
                        header(headers, "x-original-routingKey"));
            }

            // Unrouted: main exchange로, 원래 라우팅 키 그대로
            case UNROUTED -> new Destination(
                    RabbitTopologyConfig.EXCHANGE_MAIN,
                    response.getEnvelope().getRoutingKey());
        };
    }


    private String header(Map<String, Object> headers, String key) {
        if(Objects.isNull(headers)) {
            throw new IllegalArgumentException("header가 비어있습니다.");
        }

        Object value = headers.get(key);
        return value.toString();
    }

    private record Destination(
            String exchange,
            String routingKey
    ) {}
}
