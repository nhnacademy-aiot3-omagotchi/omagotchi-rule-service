package site.omagotchi.ruleservice.global.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.BDDAssertions.then;

class AuthenticatedUserTest {

    private static final UUID USER_ID = UUID.fromString("019d2a48-80c0-4d6a-9a15-0b16d2dd74f1");

    @Test
    @DisplayName("검증된 JWT의 sub와 role을 애플리케이션 인증 값으로 변환")
    void convertsJwtSubjectAndRole() {
        // Given
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .claim("role", "SYSTEM_ADMIN")
                .build();

        // When
        AuthenticatedUser user = AuthenticatedUser.from(new JwtAuthenticationToken(jwt));

        // Then
        then(user.userId()).isEqualTo(USER_ID);
        then(user.globalRole()).isEqualTo(GlobalRole.SYSTEM_ADMIN);
    }
}
