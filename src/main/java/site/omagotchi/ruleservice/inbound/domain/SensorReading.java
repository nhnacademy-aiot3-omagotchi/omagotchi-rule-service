package site.omagotchi.ruleservice.inbound.domain;

import java.time.Instant;

public record SensorReading(
        String traceId,
        //deviceInfo.tags
        String location,
        String point,
        String deviceEui,
        String measurement,
        //payload
        double value,
        Instant measuredAt,
        Instant receivedAt,
        String deviceName,
        //frame
        Long fCnt

) {

}
