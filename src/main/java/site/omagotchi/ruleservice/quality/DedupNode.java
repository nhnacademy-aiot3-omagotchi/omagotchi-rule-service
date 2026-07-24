package site.omagotchi.ruleservice.quality;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.SensorReading;

import java.time.Duration;
import java.util.Map;

public class DedupNode extends AbstractNode {

    private final Cache<String,Boolean> seen = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100000)
            .build();

    public DedupNode(String id) {
        super(id);
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("duplicate");
        addOutputPort("delayed");
    }

    @Override
    protected void onProcess(Message message) {
        SensorReading sensorReading = message.get("sensorReading");

        String key = sensorReading.deviceEui()+":"+sensorReading.measurement()+":"+sensorReading.measuredAt();
        Duration gap = Duration.between(sensorReading.measuredAt(),sensorReading.receivedAt());

        //중복 판정
        if (seen.getIfPresent(key) != null){
            QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.DUPLICATE,"중복: "+key);
            send("duplicate", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));
            return;
        }
        else {
            seen.put(key,true);

            //지연 판정
            if(gap.getSeconds() > 60){
                send("out", message.withEntry("_delayed",true));
                QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.DELAYED, "지연: " + gap.getSeconds() + "초");
                send("delayed", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));
            }
            else {
                send("out", message);
            }
        }
    }
}
