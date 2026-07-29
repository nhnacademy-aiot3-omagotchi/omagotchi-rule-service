package site.omagotchi.ruleservice.recovery.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.recovery.application.ReplayService;
import site.omagotchi.ruleservice.recovery.domain.ParkingQueue;
import site.omagotchi.ruleservice.recovery.domain.ReplayResult;

/**
 * 수동 replay 트리거 (actuator).
 * 원인 수정 + 재배포 후 사람이 실행한다. 건수는 기동 알림/ depth 게이지(rabbitmq.parked)로 확인.
 * curl -X POST localhost:8080/actuator/replay \
 *   -H 'Content-Type: application/json' -d '{"queue":"dead-letter","max":100}'
 */
@Component
@Endpoint(id = "replay")
@RequiredArgsConstructor
public class ReplayEndpoint {

    private static final int DEFAULT_MAX = 100;

    private final ReplayService replayService;

    @WriteOperation // POST
    public ReplayResult replay(String queue, Integer max) {
        ParkingQueue parkingQueue = parse(queue);
        int limit = (max == null) ? DEFAULT_MAX : max;      // 한 번에 최대 건수
        return replayService.replay(parkingQueue, limit);
    }

    private ParkingQueue parse(String queue) {
        return switch (queue == null ? "" : queue.toLowerCase()) {
            case "dead-letter"-> ParkingQueue.DEAD_LETTER;
            case "unrouted" -> ParkingQueue.UNROUTED;
            default -> throw new IllegalArgumentException("알 수 없는 queue: " + queue);
        };
    }
}
