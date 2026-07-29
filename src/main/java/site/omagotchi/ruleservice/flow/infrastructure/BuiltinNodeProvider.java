package site.omagotchi.ruleservice.flow.infrastructure;

import site.omagotchi.ruleservice.flow.domain.node.CollectorNode;

import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;

import java.util.List;

@Component
public class BuiltinNodeProvider implements NodeProvider {

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                new NodeDescriptor("Collector", "수집 노드(테스트용)"
                        ,config -> {
                    String id = config.get("id").toString();
                    return new CollectorNode(id);
                })
        );
    }
}
