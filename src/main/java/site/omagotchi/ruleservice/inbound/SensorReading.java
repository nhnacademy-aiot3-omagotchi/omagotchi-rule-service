package site.omagotchi.ruleservice.inbound;

import java.time.Instant;

public record SensorReading(
        String traceId,
        //topic
        String location,
        String point,
        String deviceEui,
        String measurement,
        //payload
        double value,
        Instant measuredAt,
        Instant receivedAt,
        String deviceName
) {

}
