package site.omagotchi.ruleservice.recovery.application.port;

import site.omagotchi.ruleservice.recovery.domain.ParkingQueue;

public interface MessageReplayer {

    /**
     * @param queue 대상 파킹 큐
     * @param max   한 번에 처리할 최대 건수
     * @return 재발행된 건수
     */
    int replay(ParkingQueue queue, int max);
}
