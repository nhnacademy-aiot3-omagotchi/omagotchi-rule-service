package site.omagotchi.ruleservice.recovery.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * raw 데이터는 quality보다 자주 들어오는 데이터기때문에
 * 로그에 발생마다 찍는건 로그가 방대해진다.
 * </p>
 * 로그는 최소한의 정보만 표시하는게 목적이기때문에 플래그를 통해 묶음으로 몇건이 실패했는지를 표시한다.*/
@Slf4j
@Component
public class RawFailureTracker {

    private final AtomicReference<FailureState> state = new AtomicReference<>(FailureState.none());

    /** DLQ로 넘어가는 시점을 표시하는 간단 로그. state 플래그를 통해 카운트를 하나씩 증가*/
    public void onParked(Throwable cause){
        FailureState previous = state.getAndUpdate(FailureState::parked);

        if(!previous.failing()){
            log.error("raw 적재 실패 시작. 이후 동일 실패는 rabbitmq.dead-letter 카운터로 집계", cause);
        }
    }

    /** 소비가 성공하는 시점에 플래그를 다시 전환 */
    public void onSuccess(){
        FailureState previous = state.getAndUpdate(FailureState::recovered);
        if(previous.failing()){
            log.info("raw 적재 회복. 실패 메세지는 DLQ 이관 {}건", previous.parkedCount());//4
        }
    }
}