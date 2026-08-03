package site.omagotchi.ruleservice.quality.infrastructure;

import site.omagotchi.ruleservice.quality.domain.PhysicalRangeTable;
import site.omagotchi.ruleservice.quality.domain.LastSeenRegistry;

import site.omagotchi.ruleservice.quality.domain.FrameCheckNode;
import site.omagotchi.ruleservice.quality.domain.RangeValidatorNode;
import site.omagotchi.ruleservice.quality.domain.StuckSensorNode;
import site.omagotchi.ruleservice.quality.domain.DisconnectDetectorNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;

import java.util.List;

@Component
@RequiredArgsConstructor
public class QualityNodeProvider implements NodeProvider {

    private final PhysicalRangeTable physicalRangeTable;
    private final LastSeenRegistry lastSeenRegistry;
    private final QualityProperties qualityProperties;

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                // 1. RangeValidator
                new NodeDescriptor("RangeValidator", "물리 범위 이상치 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new RangeValidatorNode(id, physicalRangeTable);
                }),
                // 2. FrameCheck
                new NodeDescriptor("FrameCheck", "프레임 도착 품질 판정 노드 (중복/지연/결측)", config -> {
                    String id = (String) config.get("id");
                    return new FrameCheckNode(id);
                }),
                // 3. StuckSensor
                new NodeDescriptor("StuckSensor", "무변동 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new StuckSensorNode(id);
                }),
                // 4. DisconnectDetector
                new NodeDescriptor("DisconnectDetector", "끊김 판정 노드", config -> {
                    String id = (String) config.get("id");
                    return new DisconnectDetectorNode(id, lastSeenRegistry, qualityProperties);
                })
        );
    }
}
