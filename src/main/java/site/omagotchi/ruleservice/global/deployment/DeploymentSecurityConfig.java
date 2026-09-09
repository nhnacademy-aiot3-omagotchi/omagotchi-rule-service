package site.omagotchi.ruleservice.global.deployment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 업무 인증과 분리한 컨테이너 내부 배포 관리 경계. */
@Configuration
public class DeploymentSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain deploymentSecurityFilterChain(HttpSecurity http) {
        return http
                .securityMatcher("/actuator/registry", "/actuator/serviceregistry")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().access((authentication, context) -> {
                    // 전달 Header가 아닌 실제 연결 주소 확인. 컨테이너 내부의 127.0.0.1·::1만 허용.
                    String remote = context.getRequest().getRemoteAddr();
                    boolean loopback = "127.0.0.1".equals(remote)
                            || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
                    return new AuthorizationDecision(loopback);
                }))
                .build();
    }
}
