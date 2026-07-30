package site.omagotchi.ruleservice.distributed.domain;

import java.util.Objects;

public record EngineInfo(
        String engineId,
        String host,
        int port,
        int priority,
        long startedAt,
        PresenceStatus presenceStatus
) {
    public EngineInfo {
        if (Objects.isNull(engineId) || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId가 null이거나 비어있습니다.");
        }

        if (Objects.isNull(presenceStatus)) {
            throw new IllegalArgumentException("presenceStatus가 null입니다.");
        }
    }

    public EngineInfo withPresenceStatus(PresenceStatus newPresenceStatus) {
        return new EngineInfo(engineId, host, port, priority, startedAt, newPresenceStatus);
    }
}