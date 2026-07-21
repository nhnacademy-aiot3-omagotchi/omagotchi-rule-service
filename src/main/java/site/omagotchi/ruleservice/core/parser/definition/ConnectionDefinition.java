package site.omagotchi.ruleservice.core.parser.definition;

import java.util.Objects;

public record ConnectionDefinition(
        String from,
        String to
) {
    public ConnectionDefinition {
        if (Objects.isNull(from) || from.isBlank()) {
            throw new IllegalArgumentException("from이 null이거나 비어있습니다.");
        }

        if (Objects.isNull(to) || to.isBlank()) {
            throw new IllegalArgumentException("to가 null이거나 비어있습니다.");
        }

        if (!from.contains(":")) {
            throw new IllegalArgumentException("from은 'node:port' 형식이어야 합니다: " + from);
        }

        if (!to.contains(":")) {
            throw new IllegalArgumentException("to는 'node:port' 형식이어야 합니다: " + to);
        }

        // "nodeA:", ":" 처럼 콜론은 있지만 노드ID나 포트명이 빈 문자열인 경우 방어
        String[] fromParts = from.split(":", 2);
        if (fromParts[0].isBlank() || fromParts[1].isBlank()) {
            throw new IllegalArgumentException("from의 노드ID와 포트명은 비어있을 수 없습니다: " + from);
        }

        String[] toParts = to.split(":", 2);
        if (toParts[0].isBlank() || toParts[1].isBlank()) {
            throw new IllegalArgumentException("to의 노드ID와 포트명은 비어있을 수 없습니다: " + to);
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