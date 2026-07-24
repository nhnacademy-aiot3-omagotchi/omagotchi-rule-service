package site.omagotchi.ruleservice.rule.infrastructure.messaging;

import org.springframework.amqp.rabbit.connection.CorrelationData;

public class PendingCorrelationData extends CorrelationData {
    private final PendingMessage pendingMessage;

    public PendingCorrelationData(PendingMessage pendingMessage){
        super(pendingMessage.traceId());
        this.pendingMessage = pendingMessage;
    }

    public PendingMessage pending(){
        return pendingMessage;
    }
}
