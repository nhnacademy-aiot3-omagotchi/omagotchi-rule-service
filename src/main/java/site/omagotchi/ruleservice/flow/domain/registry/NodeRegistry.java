package site.omagotchi.ruleservice.flow.domain.registry;

import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NodeRegistry {

    private final Map<String, NodeDescriptor> descriptors = new ConcurrentHashMap<>();

    public void register(NodeDescriptor nodeDescriptor) {
        if (Objects.isNull(nodeDescriptor)) {
            throw new IllegalArgumentException("nodeDescriptor가 null입니다.");
        }

        if (descriptors.containsKey(nodeDescriptor.typeName())) {
            throw new IllegalStateException("이미 등록된 노드 타입입니다: " + nodeDescriptor.typeName());
        }

        descriptors.put(nodeDescriptor.typeName(), nodeDescriptor);
    }

    public AbstractNode create(String typeName, Map<String, Object> config) {
        if (Objects.isNull(typeName) || typeName.isBlank()) {
            throw new IllegalArgumentException("typeName이 null이거나 비어있습니다.");
        }

        NodeDescriptor nodeDescriptor = descriptors.get(typeName);
        if (Objects.isNull(nodeDescriptor)) {
            throw new IllegalArgumentException("등록되지 않은 노드 타입입니다: " + typeName + " (등록된 타입: " + getRegisteredTypes() + ")");
        }

        return nodeDescriptor.nodeFactory().create(config);
    }

    public Set<String> getRegisteredTypes() {
        return Set.copyOf(descriptors.keySet());
    }

    public boolean isRegistered(String typeName) {
        return descriptors.containsKey(typeName);
    }
}