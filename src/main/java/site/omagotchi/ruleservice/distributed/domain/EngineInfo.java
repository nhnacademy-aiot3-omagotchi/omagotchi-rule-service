package site.omagotchi.ruleservice.distributed.domain;

import java.util.Objects;

public record EngineInfo(
        String engineId,
        String host,
        int port,
        int priority,
        long startedAt,
        PresenceStatus presenceStatus,
        EngineRole engineRole // 자기 자신 항목은 항상 null로 두고 응답 조립 시점에 덮어쓰는 것이 의도된 동작 (nullable - 검증X)
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
        return new EngineInfo(engineId, host, port, priority, startedAt, newPresenceStatus, engineRole);
    }
}