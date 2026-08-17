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

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class QualityNodeProvider implements NodeProvider {

    private static final String TYPE_RANGE_VALIDATOR = "RangeValidator";
    private static final String TYPE_FRAME_CHECK = "FrameCheck";
    private static final String TYPE_STUCK_SENSOR = "StuckSensor";
    private static final String TYPE_DISCONNECT_DETECTOR = "DisconnectDetector";

    private final PhysicalRangeTable physicalRangeTable;
    private final LastSeenRegistry lastSeenRegistry;
    private final QualityProperties qualityProperties;
    private final Clock clock;

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                // 1. RangeValidator
                new NodeDescriptor(TYPE_RANGE_VALIDATOR, "물리 범위 이상치 판정 노드", config -> {
                    String id = Objects.requireNonNull((String) config.get("id"), "노드 config에 id가 없습니다");
                    return new RangeValidatorNode(id, physicalRangeTable);
                }),
                // 2. FrameCheck
                new NodeDescriptor(TYPE_FRAME_CHECK, "프레임 도착 품질 판정 노드 (중복/지연/결측)", config -> {
                    String id = Objects.requireNonNull((String) config.get("id"), "노드 config에 id가 없습니다");
                    return new FrameCheckNode(id);
                }),
                // 3. StuckSensor
                new NodeDescriptor(TYPE_STUCK_SENSOR, "무변동 판정 노드", config -> {
                    String id = Objects.requireNonNull((String) config.get("id"), "노드 config에 id가 없습니다");
                    return new StuckSensorNode(id);
                }),
                // 4. DisconnectDetector
                new NodeDescriptor(TYPE_DISCONNECT_DETECTOR, "끊김 판정 노드", config -> {
                    String id = Objects.requireNonNull((String) config.get("id"), "노드 config에 id가 없습니다");
                    return new DisconnectDetectorNode(id, lastSeenRegistry, qualityProperties, this.clock);
                })
        );
    }
}
