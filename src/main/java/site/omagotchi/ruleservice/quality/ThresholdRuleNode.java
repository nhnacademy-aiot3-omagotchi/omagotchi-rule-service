package site.omagotchi.ruleservice.quality;

import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.SensorReading;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.infrastructure.cache.RuleCache;

import java.util.Map;
import java.util.Optional;

public class ThresholdRuleNode extends AbstractNode {

    private final RuleCache ruleCache;

    public ThresholdRuleNode(String id, RuleCache ruleCache) {
        super(id);
        this.ruleCache = ruleCache;
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("ruleHit");
    }

    @Override
    protected void onProcess(Message message) {
        SensorReading sensorReading = message.get("sensorReading");

        Optional<ThresholdRule> hit = ruleCache.evaluate(
                sensorReading.deviceEui(),
                sensorReading.measurement(),
                sensorReading.value()
        );

        if(hit.isPresent()){
            send("out", message.withEntry("_ruleHit", true));

            ThresholdRule rule = hit.get();
            QualityEvent qualityEvent = QualityEvent.from(sensorReading, QualityEvent.Type.RULE_HIT,
                    "룰 적중: " + sensorReading.measurement() + " " + rule.operator() + " " + rule.threshold());
            send("ruleHit",Message.of(sensorReading.traceId(), Map.of("qualityEvent",qualityEvent)));
        } else {
            send("out", message);
        }
    }
}
