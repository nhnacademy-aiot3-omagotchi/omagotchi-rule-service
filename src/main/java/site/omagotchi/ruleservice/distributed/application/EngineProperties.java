package site.omagotchi.ruleservice.distributed.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "engine")
public record EngineProperties(
        String id,
        int priority
) {
    public EngineProperties {
        if (Objects.isNull(id) || id.isBlank()) {
            throw new IllegalArgumentException("engine.id가 null이거나 비어있습니다.");
        }
    }
}