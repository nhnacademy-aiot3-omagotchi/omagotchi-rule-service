package site.omagotchi.ruleservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InternalAuthProperties의 컴팩트 생성자 제대로 동작하는지 확인
 */
class InternalAuthPropertiesTest {

    @ParameterizedTest // 똑같은 테스트 따로 쓰는 대신 한 번만 씀 (여러 파라미터로 반복 실행)
    @NullAndEmptySource // 널과 빈 문자열 두 개를 자동으로 넣어줌
    @ValueSource(strings = {" ", "\t"}) // 공백 문자열 두 개를 추가로 넣어줌
    @DisplayName("sharedSecret이 null이거나 공백이면 예외를 던진다")
    void throwsWhenSharedSecretIsNullOrBlank(String invalidSecret) {
        assertThatThrownBy(() -> new InternalAuthProperties(invalidSecret))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("유효한 sharedSecret이면 정상 생성된다")
    void createsSuccessfullyWithValidSecret() {
        assertThatCode(() -> new InternalAuthProperties("valid-secret")) // 널이 아니고 비어있지 않은
                .doesNotThrowAnyException();
    }
}