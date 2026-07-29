package site.omagotchi.ruleservice.recovery.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.config.RabbitTopologyConfig;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParkedMessageAlerter {
    private final RabbitTemplate rabbitTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void warnOnStart(){
        long deadLetter = depth(RabbitTopologyConfig.QUEUE_DEAD_LETTER);
        long unrouted = depth(RabbitTopologyConfig.QUEUE_UNROUTED);

        if(deadLetter + unrouted > 0){
            log.warn("파킹 큐에 메세지 대기중 deadLetter: {}건, unrouted: {}건", deadLetter, unrouted);
        }
    }

    private long depth(String queue){
        try{
            //채널을 하나 빌려 큐에 접근. 큐가 없다면 예외 발생 있다면 메세지 개수 카운트
            return (long) rabbitTemplate.execute(channel -> channel.queueDeclarePassive(queue).getMessageCount());
        }catch (Exception e){
            return 0L;
        }
    }
}
