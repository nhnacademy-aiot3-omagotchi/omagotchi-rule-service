package site.omagotchi.ruleservice.core.parser;

import java.util.Objects;

public record ConnectionDefinition(
        String from,
        String to
) {
    public ConnectionDefinition {
        if (Objects.isNull(from) || from.isBlank()) {
            throw new IllegalArgumentException("from이 null이거나 비어있습니다.");
        }

        if(Objects.isNull(to) || to.isBlank()) {
            throw new IllegalArgumentException("to가 null이거나 비어있습니다.");
        }

        if(!from.contains(":")) {
            throw new IllegalArgumentException("from은 'node:port' 형식이어야 합니다: " + from);
        }

        if(!to.contains(":")) {
            throw new IllegalArgumentException("to는 'node:port' 형식이어야 합니다: " + to);
        }
    }

    public String sourceNodeId() {
        return from.split(":", 2)[0];
    }

    public String sourcePort() {
        return from.split(":", 2)[1];
    }

    public String targetNodeId() {
        return to.split(":", 2)[0];
    }

    public String targetPort() {
        return to.split(":", 2)[1];
    }
}