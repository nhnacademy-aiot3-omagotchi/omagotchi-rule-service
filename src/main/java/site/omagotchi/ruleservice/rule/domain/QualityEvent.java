package site.omagotchi.ruleservice.rule.domain;

import java.time.Instant;

public record QualityEvent(
        int version,
        String traceId,
        Type type,

        String location,
        String point,
        String deviceEui,
        String measurement,

        Double value,
        Instant measuredAt,
        Instant receivedAt,

        String detail
) {
    public enum Type {
        ANOMALY, MISSING, DUPLICATE, DELAYED, STUCK, RULE_HIT, INVALID
    }
}