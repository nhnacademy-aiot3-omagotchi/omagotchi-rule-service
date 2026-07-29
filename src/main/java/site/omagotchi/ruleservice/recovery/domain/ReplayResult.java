package site.omagotchi.ruleservice.recovery.domain;

/**
 * replay 실행 결과 값 객체.
 *
 * @param queue    대상 파킹 큐
 * @param replayed 재발행된 건수
 */
public record ReplayResult(
        ParkingQueue queue,
        int replayed) {
}
