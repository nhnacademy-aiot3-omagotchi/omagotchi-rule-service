package site.omagotchi.ruleservice.rule.infrastructure.messaging;

import site.omagotchi.ruleservice.rule.infrastructure.messaging.node.PublishMode;

public record PendingMessage(
        String exchange,
        String routingKey,
        Object body,
        String traceId,
        PublishMode mode
) {}
