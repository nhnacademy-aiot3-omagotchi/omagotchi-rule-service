package site.omagotchi.ruleservice.rule.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.BDDAssertions.then;

class LearningClientPropertiesTest {

    private static final String VALID_PASSWORD = "test-only-rule-learning-password";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("Learning Client 설정 바인딩과 비밀번호 마스킹")
    void bindsValidPropertiesAndRedactsCredential() {
        contextRunner
                .withPropertyValues(
                        "learning.base-url=http://localhost:8084",
                        "learning.username=rule-service",
                        "learning.password=" + VALID_PASSWORD
                )
                .run(context -> {
                    then(context).hasNotFailed();
                    LearningClientProperties properties =
                            context.getBean(LearningClientProperties.class);
                    then(properties.baseUrl()).isEqualTo("http://localhost:8084");
                    then(properties.username()).isEqualTo("rule-service");
                    then(properties.password()).isEqualTo(VALID_PASSWORD);
                    then(properties.toString())
                            .contains("[REDACTED]")
                            .doesNotContain(VALID_PASSWORD);
                });
    }

    @Test
    @DisplayName("Learning Client Credential 누락의 기동 실패")
    void rejectsMissingCredential() {
        contextRunner
                .withPropertyValues("learning.base-url=http://localhost:8084")
                .run(context -> then(context.getStartupFailure())
                        .isNotNull()
                        .hasStackTraceContaining("learning.username은 비어 있을 수 없습니다.")
                        .hasStackTraceContaining("learning.password는 비어 있을 수 없습니다."));
    }

    @ParameterizedTest
    @ValueSource(ints = {31, 73})
    @DisplayName("허용 범위를 벗어난 Learning Client Credential 비밀번호 길이 거절")
    void rejectsInvalidPasswordLength(int length) {
        contextRunner
                .withPropertyValues(
                        "learning.base-url=http://localhost:8084",
                        "learning.username=rule-service",
                        "learning.password=" + "a".repeat(length)
                )
                .run(context -> then(context.getStartupFailure())
                        .isNotNull()
                        .hasStackTraceContaining(
                                "learning.password는 32자 이상 72자 이하여야 합니다."
                        ));
    }

    @Test
    @DisplayName("지원하지 않는 Learning Client Credential 문자 거절")
    void rejectsInvalidCredentialCharacters() {
        contextRunner
                .withPropertyValues(
                        "learning.base-url=http://localhost:8084",
                        "learning.username=rule:service",
                        "learning.password=" + "a".repeat(31) + "+"
                )
                .run(context -> then(context.getStartupFailure())
                        .isNotNull()
                        .hasStackTraceContaining("learning.username에는 ':'를 사용할 수 없습니다.")
                        .hasStackTraceContaining(
                                "learning.password는 영문자·숫자·'-'·'_'만 사용할 수 있습니다."
                        ));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LearningClientProperties.class)
    static class PropertiesConfig {
    }
}
