package site.omagotchi.ruleservice.messaging.infrastructure;

import site.omagotchi.ruleservice.messaging.domain.PublishMode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.quality.domain.QualityEvent;
import site.omagotchi.ruleservice.messaging.infrastructure.PendingCorrelationData;
import site.omagotchi.ruleservice.messaging.infrastructure.PendingMessage;
import site.omagotchi.ruleservice.messaging.infrastructure.PublishRetryBuffer;

@Slf4j
public class RabbitPublisherNode extends AbstractNode {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final PublishMode publishMode;
    private final PublishRetryBuffer retryBuffer;

    public RabbitPublisherNode(String id, RabbitTemplate rabbitTemplate, String exchange, PublishMode publishMode, PublishRetryBuffer retryBuffer) {
        super(id);
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.publishMode = publishMode;
        this.retryBuffer = retryBuffer;
        addInputPort("in");
    }

    @Override
    protected void onProcess(Message message) {
        if (publishMode == PublishMode.RAW){

            SensorReading sensorReading = message.get("sensorReading");
            if(sensorReading == null){
                return;
            }

            String routingKey = "raw." + sensorReading.location() + "." + sensorReading.measurement();
            publish(routingKey, sensorReading, message.getTraceId());
        }else{

            QualityEvent qualityEvent = message.get("qualityEvent");
            if(qualityEvent == null){
                return;
            }

            String token = qualityEvent.type().name().toLowerCase().replace("_", "");
            String routingKey = "quality." + token + "." + qualityEvent.deviceEui();
            publish(routingKey, qualityEvent, message.getTraceId());
        }
    }

    private void publish(String routingKey, Object body, String traceId){
        PendingMessage pendingMessage = new PendingMessage(exchange, routingKey, body, traceId, publishMode);

        try{
            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> {
                message.getMessageProperties().setHeader("traceId", traceId);
                return message;
            }, new PendingCorrelationData(pendingMessage));

        }catch (Exception e){
            log.error("메시지 발행 실패 - PublishRetryBuffer 적재. routingKey={}, traceId={}", routingKey, traceId, e);
            retryBuffer.offer(pendingMessage);
        }
    }
}
