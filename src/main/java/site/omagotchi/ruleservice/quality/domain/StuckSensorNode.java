package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.quality.domain.QualityEvent;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class StuckSensorNode extends AbstractNode {

    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(30);
    private static final Duration RE_ALERT_INTERVAL = Duration.ofHours(6);
    private final Map<String, StuckState> stateMap = new ConcurrentHashMap<>();

    public StuckSensorNode(String id) {
        super(id);
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("stuck");
    }

    //마지막으로 본 값, 시각, 마지막 알림 시각
    private record StuckState(
            double lastValue, Instant since, Instant lastAlertedAt
    ){

    }

    @Override
    protected void onProcess(Message message) {
        SensorReading sensorReading = message.get("sensorReading");

        //문이면 out
        if(sensorReading.measurement().equals("door")){
            send("out", message);
            return;
        }

        String key = sensorReading.deviceEui()+":"+sensorReading.measurement();
        StuckState stuckState = stateMap.get(key);
        double value = sensorReading.value();
        Instant now = sensorReading.measuredAt();

        //처음 보는 센서값이면 out
        if(stuckState == null){
            stateMap.put(key, new StuckState(value,now,null));
            send("out", message);
            return;
        }
        //값이 바뀌었으면 out
        if(value != stuckState.lastValue()){
            stateMap.put(key,new StuckState(value,now,null));
            send("out", message);
            return;
        }
        //임계값을 넘었을 경우 stuck
        Duration stuckDuration = Duration.between(stuckState.since(), now);
        boolean shouldAlert = stuckState.lastAlertedAt() == null
                || Duration.between(stuckState.lastAlertedAt(), now).compareTo(RE_ALERT_INTERVAL) > 0;
        if(stuckDuration.compareTo(STUCK_THRESHOLD) > 0 && shouldAlert){
            log.info("[무변동] {}:{} {}분",
                    sensorReading.deviceEui(), sensorReading.measurement(), stuckDuration.toMinutes());

            QualityEvent qualityEvent = QualityEvent.from(sensorReading, QualityEvent.Type.STUCK,"무변동: "+ stuckDuration.toMinutes() + "분");
            send("stuck",Message.of(sensorReading.traceId(), Map.of("qualityEvent",qualityEvent)));
            stateMap.put(key,new StuckState(value,stuckState.since(),now));
        }
        //어느 경우도 아닐 경우 out
        send("out",message);
    }
}
