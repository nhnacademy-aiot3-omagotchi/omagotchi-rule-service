package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.quality.domain.QualityEvent;
import site.omagotchi.ruleservice.quality.domain.PhysicalRange;
import site.omagotchi.ruleservice.quality.domain.PhysicalRangeTable;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.util.Map;
import java.util.Optional;

@Slf4j
public class RangeValidatorNode extends AbstractNode {

    private final PhysicalRangeTable physicalRangeTable;

    public RangeValidatorNode(String id, PhysicalRangeTable physicalRangeTable) {
        super(id);
        this.physicalRangeTable = physicalRangeTable;
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("anomaly");
    }

    @Override
    protected void onProcess(Message message) {
        SensorReading sensorReading = message.get("sensorReading");
        Optional<PhysicalRange> rangeOptional = physicalRangeTable.rangeOf(sensorReading.measurement());

        if(rangeOptional.isEmpty()){
            send("out", message);
            return;
        }

        PhysicalRange range = rangeOptional.get();
        double value = sensorReading.value();
        if(value >= range.min() && (range.max() == null || value <= range.max())){
            send("out", message);
        }

        else {
            log.info("[범위초과] {}:{} value={}",
                    sensorReading.deviceEui(), sensorReading.measurement(), value);

            send("out", message.withEntry("_anomaly", true));
            QualityEvent qualityEvent = QualityEvent.from(sensorReading, QualityEvent.Type.ANOMALY,"범위 초과: " + sensorReading.measurement() + " " + value);
            send("anomaly", Message.of(sensorReading.traceId(), Map.of("qualityEvent", qualityEvent)));
        }
    }
}