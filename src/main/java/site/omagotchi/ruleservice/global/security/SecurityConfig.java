package site.omagotchi.ruleservice.global.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    RequestMatcher internalApiRequestMatcher() {
        return PathPatternRequestMatcher.withDefaults()
                .matcher("/api/v1/internal/**");
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseHandler errorHandler,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            RequestMatcher internalApiRequestMatcher
    ) {
        http
                // Access Token은 Bearer Header 사용
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authorize -> authorize
                        // 오류 처리 재디스패치가 인증 검사에 다시 막히지 않도록 허용
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 외부 라우팅 확인용 Smoke 경로만 공개
                        .requestMatchers(HttpMethod.GET, "/api/v1/rules/ping").permitAll()
                        // 배포 확인·내부 메트릭 수집용 Actuator 경로
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/prometheus",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/metrics/**"
                        ).permitAll()
                        // Spring Security와 내부 Credential Filter의 경로 판정 기준 공유
                        .requestMatchers(internalApiRequestMatcher).permitAll()
                        // 룰 캐시와 플로우 제어 API는 운영 화면의 시스템 관리자 기능
                        .requestMatchers(
                                "/api/v1/rules",
                                "/api/v1/rules/**",
                                "/api/v1/flows",
                                "/api/v1/flows/**",
                                "/api/v1/engines",
                                "/api/v1/recovery/**"
                        ).hasRole("SYSTEM_ADMIN")
                        // 새 경로를 실수로 공개하지 않도록 명시된 경계 밖은 거부
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                );

        return http.build();
    }
}
