package site.omagotchi.ruleservice.quality;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.core.registry.NodeDescriptor;
import site.omagotchi.ruleservice.core.registry.NodeProvider;
import site.omagotchi.ruleservice.rule.infrastructure.cache.RuleCache;

import java.util.List;

@Component
@RequiredArgsConstructor
public class QualityNodeProvider implements NodeProvider {

    private final PhysicalRangeTable physicalRangeTable;
    private final LastSeenRegistry lastSeenRegistry;
    private final QualityProperties qualityProperties;
    private final RuleCache ruleCache;

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                // 1. RangeValidator (기존)
                new NodeDescriptor("RangeValidator", "물리 범위 이상치 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new RangeValidatorNode(id, physicalRangeTable);
                }),
                // 2. Dedup
                new NodeDescriptor("Dedup", "중복/지연 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new DedupNode(id);
                }),
                // 3. StuckSensor
                new NodeDescriptor("StuckSensor", "무변동 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new StuckSensorNode(id);
                }),
                // 4. MissingDetector
                new NodeDescriptor("MissingDetector", "결측 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new MissingDetectorNode(id, lastSeenRegistry, qualityProperties);
                }),
                // 5. ThresholdRule
                new NodeDescriptor("ThresholdRule", "임계값 룰 평가 노드", config -> {
                    String id = (String) config.get("id");
                    return new ThresholdRuleNode(id, ruleCache);
                })
        );
    }
}
