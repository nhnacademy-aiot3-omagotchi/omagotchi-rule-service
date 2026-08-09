package site.omagotchi.ruleservice.recovery.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.recovery.domain.ReplayResult;
import site.omagotchi.ruleservice.recovery.infrastructure.MessageReplayer;

@Slf4j
@RequiredArgsConstructor
@Service
public class ReplayService {

    private final MessageReplayer messageReplayer;

    public ReplayResult replay(int max){
        int replayed = messageReplayer.replay(max);
        log.info("replay 실행 {}건", replayed);
        return new ReplayResult(replayed);
    }
}
