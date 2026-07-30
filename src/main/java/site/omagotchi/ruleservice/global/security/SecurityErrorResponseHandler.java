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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Controller 이전에 발생한 Security 예외를 공통 JSON 응답으로 변환
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String MDC_REQUEST_ID_KEY = "requestId";

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
        accessDeniedHandler.handle(request, response, exception);
        write(response, SecurityErrorCode.ACCESS_DENIED, request.getRequestURI());
    }

    private void write(
            HttpServletResponse response,
            ErrorCode errorCode,
            String path
    ) throws IOException {
        ApiErrorResponse body = new ApiErrorResponse(
                response.getStatus(),
                errorCode.code(),
                errorCode.message(),
                path,
                MDC.get(MDC_REQUEST_ID_KEY)
        );

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
