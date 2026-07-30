package site.omagotchi.ruleservice.recovery.infrastructure;

import site.omagotchi.ruleservice.recovery.application.port.MessageReplayer;

import com.rabbitmq.client.GetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.recovery.domain.ParkingQueue;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * basicGet(autoAck=false)으로 한 건 꺼내되, 재발행 성공 후에만 ack한다 —
 * 발행 실패 시 파킹 큐에서 사라지는 유실을 막기 위함.
 * 원 목적지를 못 찾은 메시지(예: x-original-* 없이 x-death만 달린 브로커 데드레터링 건)는
 * 루프 도중 unacked로 붙잡아 두었다가 종료 후 한꺼번에 nack(requeue)로 보존한다 —
 * 즉시 requeue하면 다음 basicGet이 같은 걸 다시 꺼내 진행이 막히기 때문.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMessageReplayer implements MessageReplayer {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public int replay(ParkingQueue queue, int max) {
        return rabbitTemplate.execute(channel -> {
            List<Long> skipped = new ArrayList<>(); // 목적지 못 찾아 건너뛴 것 (루프 종료 후 한꺼번에 보존)
            int count = 0;
            for (int i = 0; i < max; i++) {
                GetResponse response = channel.basicGet(sourceQueue(queue), false); // autoAck=false
                if (response == null) {
                    break; // 큐가 비면 종료
                }

                long deliveryTag = response.getEnvelope().getDeliveryTag();

                Destination destination = resolveDestination(queue, response);
                if (destination.exchange() == null) {
                    // 지금 nack(requeue)하면 다음 basicGet이 같은 메시지를 다시 꺼내 진행이 막힌다(head-of-line blocking).
                    // 루프 동안 unacked로 붙잡아 두면 다음 basicGet이 그 다음 메시지를 꺼내고, 끝나고 되돌린다.
                    skipped.add(deliveryTag);
                    log.warn("원 목적지 헤더 없는 메시지 건너뜀 (x-original-* 부재, 사람 확인 필요). queue={}, deliveryTag={}",
                            queue, deliveryTag);
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

            // 건너뛴 메시지는 큐에 그대로 보존(유실 없음). 순서상 head로 돌아가지만 다음 replay에서도 동일하게 skip된다.
            for (long tag : skipped) {
                channel.basicNack(tag, false, true);
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
            return null;
        }

        Object value = headers.get(key);
        // 키 부재 시 NPE 대신 null (예: x-death만 있고 x-original-* 없는 브로커 데드레터링 메시지)
        return Objects.isNull(value) ? null : value.toString();
    }

    private record Destination(
            String exchange,
            String routingKey
    ) {}
}
