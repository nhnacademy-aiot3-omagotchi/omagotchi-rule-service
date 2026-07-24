package site.omagotchi.ruleservice.quality;

import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.SensorReading;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StuckSensorNode extends AbstractNode {

    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(30);
    private final Map<String, StuckState> stateMap = new ConcurrentHashMap<>();

    public StuckSensorNode(String id) {
        super(id);
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("stuck");
    }

    //마지막으로 본 값, 시각, 알림 여부
    private record StuckState(
            double lastValue, Instant since, boolean alerted
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
            stateMap.put(key, new StuckState(value,now,false));
            send("out", message);
            return;
        }
        //값이 바뀌었으면 out
        if(value != stuckState.lastValue()){
            stateMap.put(key,new StuckState(value,now,false));
            send("out", message);
            return;
        }
        //값이 같고 임계값을 넘었을 경우 stuck
        Duration stuckDuration = Duration.between(stuckState.since(), now);
        if(stuckDuration.compareTo(STUCK_THRESHOLD) > 0 && !stuckState.alerted()){
            QualityEvent qualityEvent = QualityEvent.from(sensorReading, QualityEvent.Type.STUCK,"무변동: "+ stuckDuration.toMinutes() + "분");
            send("stuck",Message.of(sensorReading.traceId(), Map.of("qualityEvent",qualityEvent)));
            stateMap.put(key,new StuckState(value,stuckState.since(),true));
        }
        //어느 경우도 아닐 경우 out
        send("out",message);
    }
}
