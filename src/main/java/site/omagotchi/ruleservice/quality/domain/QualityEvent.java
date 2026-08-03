package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.inbound.domain.SensorReading;

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
        ANOMALY,        // 물리범위 밖 [범위초과]
        MISSING,        // fCnt 갭 - 프레임이 영영 없음 [결측]
        DUPLICATE,      // 같은 프레임 재도착 [중복]
        DELAYED,        // 늦은 도착 [지연]
        STUCK,          // 값 고정 [무변동]
        RULE_HIT,       // 룰 조건 충족 [룰적중]
        INVALID,        // 판독 불가 [무효]
        DISCONNECTED    // 주기 3배 침묵 상태 [끊김 시작/종료]
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

    public static QualityEvent disconnected(String deviceEui, String measurement, String detail) {
        return new QualityEvent(
                1,
                null,
                Type.DISCONNECTED,
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