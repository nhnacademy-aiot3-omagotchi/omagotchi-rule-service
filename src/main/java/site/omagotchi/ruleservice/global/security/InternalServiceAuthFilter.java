package site.omagotchi.ruleservice.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import site.omagotchi.ruleservice.global.exception.ApiErrorResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

@Component
@Slf4j
@RequiredArgsConstructor
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    private final InternalAuthProperties internalAuthProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(INTERNAL_TOKEN_HEADER);

        if (!this.matchesSharedSecret(token)) {
            this.reject(request, response);

            return;
        }

        filterChain.doFilter(request, response);
    }

    // 타이밍 공격 방지 - String.equals()는 첫 불일치 문자에서 바로 반환해 비교 시간이 세어나갈 수 있어 상수 시간 비교 사용
    private boolean matchesSharedSecret(String rawToken) {
        if (Objects.isNull(rawToken)) {
            return false;
        }

        return MessageDigest.isEqual(
                rawToken.getBytes(StandardCharsets.UTF_8),
                this.internalAuthProperties.sharedSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();

        log.warn("[{}] 공유 시크릿 헤더 검증 실패 - 접근 거부 (remoteAddr = {})", path, request.getRemoteAddr());

        response.setStatus(HttpStatus.FORBIDDEN.value());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiErrorResponse body = new ApiErrorResponse(
                SecurityErrorCode.ACCESS_DENIED.code(),
                SecurityErrorCode.ACCESS_DENIED.message(),
                path,
                MDC.get(MDC_REQUEST_ID_KEY)
        );

        this.objectMapper.writeValue(response.getOutputStream(), body);
    }
}
