package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.time.Instant;

/**
 * learning-service로 나가는 품질 이벤트 계약.
 *
 * <p>operator·threshold는 RULE_HIT일 때만 채워진다. 히트 <b>시점의</b> 조건을 그대로 실어 보내
 * 소비자가 나중에 DB를 다시 읽지 않게 한다 — 그 사이 관리자가 임계를 바꾸면 틀린 값이 된다.</p>
 *
 * <p>operator를 Operator enum이 아니라 String으로 보내는 이유는 quality → rule 순환 의존을
 * 만들지 않기 위해서다. 소비자는 상수 이름으로 역직렬화한다.</p>
 */
public record QualityEvent(
        String traceId,
        Type type,

        String location,
        String point,
        String deviceEui,
        String measurement,

        Double value,         // 래퍼 Double! MISSING(결측)은 값이 없어서 null 가능
        String detail,        // 판정 사유 (예: "co2 4200 > 임계 1000")

        String operator,      // RULE_HIT 외에는 null. GT / GTE / LT / LTE
        Double threshold,     // RULE_HIT 외에는 null

        Instant measuredAt,
        Instant receivedAt
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

    /** RULE_HIT 외 판정용. operator·threshold는 담기지 않는다 */
    public static QualityEvent from(SensorReading sensorReading, Type type, String detail){
        return new QualityEvent(
                sensorReading.traceId(),
                type,
                sensorReading.location(),
                sensorReading.point(),
                sensorReading.deviceEui(),
                sensorReading.measurement(),
                sensorReading.value(),
                detail,
                null,
                null,
                sensorReading.measuredAt(),
                sensorReading.receivedAt()
        );
    }

    /**
     * 룰 적중 전용. 히트시킨 조건을 함께 싣는다.
     *
     * @param operator ThresholdRule.operator().name()
     */
    public static QualityEvent ruleHit(SensorReading sensorReading, String operator, double threshold){
        return new QualityEvent(
                sensorReading.traceId(),
                Type.RULE_HIT,
                sensorReading.location(),
                sensorReading.point(),
                sensorReading.deviceEui(),
                sensorReading.measurement(),
                sensorReading.value(),
                "룰 적중: " + sensorReading.measurement() + " " + operator + " " + threshold,
                operator,
                threshold,
                sensorReading.measuredAt(),
                sensorReading.receivedAt()
        );
    }

    public static QualityEvent invalid(String traceId, String detail) {
        return new QualityEvent(
                traceId,
                Type.INVALID,
                null,
                null,
                null,
                null,
                null,
                detail,
                null,
                null,
                null,
                Instant.now()
        );
    }

    /** 끊김 이벤트는 특정 메시지에 대한 응답이 아니라 타이머(DisconnectDetectorNode.check())가
     *  주기적으로 만들어내는 이벤트라 traceId가 의도적으로 null이다.
     *  발행 헤더의 traceId는 이 값이 아니라 Message가 자체 발급한 UUID를 쓰므로
     *  (RabbitPublisherNode.publish() 참고) 다운스트림에 null이 흐르지 않는다. */
    public static QualityEvent disconnected(String deviceEui, String measurement, String detail) {
        return new QualityEvent(
                null,
                Type.DISCONNECTED,
                null,
                null,
                deviceEui,
                measurement,
                null,
                detail,
                null,
                null,
                null,
                Instant.now()
        );
    }
}