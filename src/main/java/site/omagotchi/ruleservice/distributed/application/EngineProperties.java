package site.omagotchi.ruleservice.distributed.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "engine")
public record EngineProperties(
        String id,
        int priority,
        int expectedPeerCount
) {
    public EngineProperties {
        if (Objects.isNull(id) || id.isBlank()) {
            throw new IllegalArgumentException("engine.id가 null이거나 비어있습니다.");
        }

        if (expectedPeerCount < 0) {
            throw new IllegalArgumentException("engine.expected-peer-count는 0 이상이어야 합니다.");
        }
    }
}