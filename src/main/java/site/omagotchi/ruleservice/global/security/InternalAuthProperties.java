package site.omagotchi.ruleservice.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * 공유 시크릿 설정
 */
@ConfigurationProperties(prefix = "internal")
public record InternalAuthProperties(
        String sharedSecret
) {
    public InternalAuthProperties {
        if (Objects.isNull(sharedSecret) || sharedSecret.isBlank()) {
            throw new IllegalArgumentException("internal.shared-secret이 null이거나 비어있습니다.");
        }
    }
}
