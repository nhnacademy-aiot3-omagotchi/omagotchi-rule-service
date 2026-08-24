package site.omagotchi.ruleservice.flow.domain.registry;

import java.util.Objects;

public record NodeDescriptor(
        String typeName,
        String description,
        NodeFactory nodeFactory
) {
    public NodeDescriptor {
        if (Objects.isNull(typeName) || typeName.isBlank()) {
            throw new IllegalArgumentException("typeName이 null이거나 비어있습니다.");
        }

        if (Objects.isNull(nodeFactory)) {
            throw new IllegalArgumentException("nodeFactory가 null입니다.");
        }
    }
}