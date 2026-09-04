package site.omagotchi.ruleservice.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import site.omagotchi.ruleservice.global.exception.ApiErrorResponse;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 내부 API 경로의 서비스 공유 Secret 검증과 안전한 거부 응답 기록. */
@Component
@Slf4j
@RequiredArgsConstructor
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private final InternalAuthProperties internalAuthProperties;
    private final ObjectMapper objectMapper;
    private final RequestMatcher internalApiRequestMatcher;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !this.internalApiRequestMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String token = request.getHeader(InternalAuthHeader.NAME);

        if (!this.matchesSharedSecret(token)) {
            this.reject(request, response);

            return;
        }

        filterChain.doFilter(request, response);
    }

    // 공유 Secret 비교의 상수 시간 보장
    private boolean matchesSharedSecret(@Nullable String rawToken) {
        if (rawToken == null) {
            return false;
        }

        return MessageDigest.isEqual(
                rawToken.getBytes(StandardCharsets.UTF_8),
                this.internalAuthProperties.sharedSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();

        log.atWarn()
                .addKeyValue("event.dataset", "rule-service.security")
                .addKeyValue("event.action", "internal.authentication.failed")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("error.code", SecurityErrorCode.ACCESS_DENIED.code())
                .addKeyValue("http.request.method", request.getMethod())
                .addKeyValue("http.response.status_code", HttpStatus.FORBIDDEN.value())
                .log("Internal service authentication rejected");

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiErrorResponse body = new ApiErrorResponse(
                SecurityErrorCode.ACCESS_DENIED.code(),
                SecurityErrorCode.ACCESS_DENIED.message(),
                path,
                MDC.get(RequestIdContext.MDC_KEY)
        );

        this.objectMapper.writeValue(response.getOutputStream(), body);
    }
}
