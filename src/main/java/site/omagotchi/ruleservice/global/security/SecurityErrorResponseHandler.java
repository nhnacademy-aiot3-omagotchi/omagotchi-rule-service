package site.omagotchi.ruleservice.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.global.exception.ApiErrorResponse;
import site.omagotchi.ruleservice.global.exception.CommonErrorCode;
import site.omagotchi.ruleservice.global.exception.ErrorCode;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security Bearer 처리 결과를 보존한 인증·인가 실패의 공통 JSON 응답.
 * 상태·{@code WWW-Authenticate} 헤더는 기존 처리기에 위임하고 공통 오류 본문만 기록.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final BearerTokenAuthenticationEntryPoint authenticationEntryPoint =
            new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler accessDeniedHandler =
            new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AuthenticationException exception
    ) throws IOException {
        // 인증 실패 상태와 WWW-Authenticate 헤더 결정을 기존 Bearer 처리기에 위임
        authenticationEntryPoint.commence(request, response, exception);
        ErrorCode errorCode = response.getStatus() == HttpStatus.BAD_REQUEST.value()
                ? CommonErrorCode.INVALID_REQUEST
                : SecurityErrorCode.AUTHENTICATION_REQUIRED;
        write(response, errorCode, request.getRequestURI());
    }

    @Override
    public void handle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AccessDeniedException exception
    ) throws IOException {
        // 인가 실패 상태와 WWW-Authenticate 헤더 결정을 기존 Bearer 처리기에 위임
        accessDeniedHandler.handle(request, response, exception);
        write(response, SecurityErrorCode.ACCESS_DENIED, request.getRequestURI());
    }

    private void write(
            HttpServletResponse response,
            ErrorCode errorCode,
            String path
    ) throws IOException {
        ApiErrorResponse body = new ApiErrorResponse(
                errorCode.code(),
                errorCode.message(),
                path,
                MDC.get(RequestIdContext.MDC_KEY)
        );

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
