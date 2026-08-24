package site.omagotchi.ruleservice.global.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "learning")
public record LearningClientProperties(
        @NotBlank(message = "learning.base-url은 비어 있을 수 없습니다.")
        String baseUrl,

        @NotBlank(message = "learning.username은 비어 있을 수 없습니다.")
        @Pattern(
                regexp = "^[^:]*$",
                message = "learning.username에는 ':'를 사용할 수 없습니다."
        )
        String username,

        @NotBlank(message = "learning.password는 비어 있을 수 없습니다.")
        String password
) {

    @AssertTrue(message = "learning.password는 32자 이상 72자 이하여야 합니다.")
    public boolean isPasswordLengthValid() {
        return password == null || password.length() >= 32 && password.length() <= 72;
    }

    @AssertTrue(message = "learning.password는 영문자·숫자·'-'·'_'만 사용할 수 있습니다.")
    public boolean isPasswordCharacterSetValid() {
        return password == null || password.matches("[A-Za-z0-9_-]+");
    }

    @Override
    public String toString() {
        return "LearningClientProperties[baseUrl=" + baseUrl
                + ", username=" + username
                + ", password=[REDACTED]]";
    }
}
