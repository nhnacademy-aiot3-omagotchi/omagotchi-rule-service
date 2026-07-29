package site.omagotchi.ruleservice.recovery.domain;

/**
 * 회복 대상 파킹 큐 개념.
 * </p>
 * DEAD_LETTER — 처리 실패로 격리된 메시지 큐 (DLQ)
 * UNROUTED — 라우팅 실패로 격리된 메시지 큐
 *
 */
public enum ParkingQueue {
    DEAD_LETTER,
    UNROUTED
}
