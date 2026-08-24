package site.omagotchi.ruleservice.messaging.infrastructure;

import site.omagotchi.ruleservice.messaging.domain.PublishMode;

public record PendingMessage(
        String exchange,
        String routingKey,
        Object body,
        String traceId,
        PublishMode mode
) {}
