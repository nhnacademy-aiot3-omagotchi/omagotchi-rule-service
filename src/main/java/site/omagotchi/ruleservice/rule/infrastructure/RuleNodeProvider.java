package site.omagotchi.ruleservice.rule.infrastructure;

import site.omagotchi.ruleservice.rule.domain.ThresholdRuleNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;
import site.omagotchi.ruleservice.rule.domain.RuleCache;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RuleNodeProvider implements NodeProvider {

    private final RuleCache ruleCache;

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                // ThresholdRule
                new NodeDescriptor("ThresholdRule", "임계값 룰 평가 노드", config -> {
                    String id = (String) config.get("id");
                    return new ThresholdRuleNode(id, ruleCache);
                })
        );
    }
}
