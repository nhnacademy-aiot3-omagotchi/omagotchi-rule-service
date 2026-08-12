package site.omagotchi.ruleservice.global.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "core")
public record CoreClientProperties(
        @NotBlank(message = "core.base-url은 비어 있을 수 없습니다.")
        String baseUrl
) {
}
