package site.omagotchi.ruleservice.rule.domain;

import site.omagotchi.ruleservice.quality.domain.QualityEvent;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.domain.RuleCache;

import java.util.Map;
import java.util.Optional;

@Slf4j
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

            log.info("[룰적중] {}:{} {} {}",
                    sensorReading.deviceEui(), sensorReading.measurement(), rule.operator(), rule.threshold());

            QualityEvent qualityEvent = QualityEvent.from(sensorReading, QualityEvent.Type.RULE_HIT,
                    "룰 적중: " + sensorReading.measurement() + " " + rule.operator() + " " + rule.threshold());
            send("ruleHit",Message.of(sensorReading.traceId(), Map.of("qualityEvent",qualityEvent)));
        } else {
            send("out", message);
        }
    }
}
