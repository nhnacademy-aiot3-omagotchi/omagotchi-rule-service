package site.omagotchi.ruleservice.recovery.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.recovery.domain.ParkingQueue;
import site.omagotchi.ruleservice.recovery.domain.ReplayResult;
import site.omagotchi.ruleservice.recovery.application.port.MessageReplayer;

/**
 * replay 유스케이스.
 * 최대건수 판단은 호출부(프레젠테이션)가 정하고, 여기서는 실행 위임 + 감사 로그 + 결과 조립을 담당한다.
 * 실제 큐 조작(어떻게)은 {@link MessageReplayer} 구현체가 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final MessageReplayer messageReplayer;

    public ReplayResult replay(ParkingQueue queue, int max) {
        int replayed = messageReplayer.replay(queue, max);
        log.warn("replay 실행: queue={}, 건수={}", queue, replayed); // 감사 로그
        return new ReplayResult(queue, replayed);
    }
}
