package site.omagotchi.ruleservice.quality;

import site.omagotchi.ruleservice.inbound.SensorReading;

import java.time.Instant;

public record QualityEvent(
        int version,          // 스키마 버전. 지금은 1로 고정 시작
        String traceId,
        Type type,            // 아래 enum

        String location,
        String point,
        String deviceEui,
        String measurement,

        Double value,         // 래퍼 Double! MISSING(결측)은 값이 없어서 null 가능
        Instant measuredAt,
        Instant receivedAt,

        String detail          // 판정 사유 (예: "co2 4200 > 임계 1000")
) {
    public enum Type {
        ANOMALY, MISSING, DUPLICATE, DELAYED, STUCK, RULE_HIT, INVALID
    }

    public static QualityEvent from(SensorReading sensorReading, Type type, String detail){
        return new QualityEvent(
                1,
                sensorReading.traceId(),
                type,
                sensorReading.location(),
                sensorReading.point(),
                sensorReading.deviceEui(),
                sensorReading.measurement(),
                sensorReading.value(),
                sensorReading.measuredAt(),
                sensorReading.receivedAt(),
                detail
        );
    }

    public static QualityEvent invalid(String traceId, String detail) {
        return new QualityEvent(
                1,
                traceId,
                Type.INVALID,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                detail
        );
    }

    public static QualityEvent missing(String deviceEui, String measurement, String detail) {
        return new QualityEvent(
                1,
                null,
                Type.MISSING,
                null,
                null,
                deviceEui,
                measurement,
                null,
                null,
                Instant.now(),
                detail
        );
    }

}