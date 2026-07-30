package site.omagotchi.ruleservice.global.auth;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        GlobalRole globalRole
) {

    public static AuthenticatedUser from(JwtAuthenticationToken authentication) {
        return new AuthenticatedUser(
                UUID.fromString(authentication.getName()),
                GlobalRole.valueOf(authentication.getToken().getClaimAsString("role"))
        );
    }
}
