package site.omagotchi.ruleservice.global.requestid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 인증 처리 이전의 Request ID 확정과 요청·응답·MDC 전파.
 * 비동기·오류 재진입 시 최초 요청에서 확정한 값의 재사용.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 비동기 재진입 Thread의 MDC에 기존 Request ID 복원
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        RequestId requestId = resolveRequestId(request);

        try (RequestIdContext.Scope ignored = RequestIdContext.openInbound(requestId)) {
            request.setAttribute(RequestId.ATTRIBUTE_NAME, requestId);
            response.setHeader(RequestId.HEADER_NAME, requestId.value());
            filterChain.doFilter(request, response);
        }
    }

    private static RequestId resolveRequestId(HttpServletRequest request) {
        // 비동기·오류 재진입 시 최초 Dispatch에서 저장한 Request ID 우선
        Object existing = request.getAttribute(RequestId.ATTRIBUTE_NAME);
        if (existing instanceof RequestId requestId) {
            return requestId;
        }

        Enumeration<String> headerValues = request.getHeaders(RequestId.HEADER_NAME);
        return RequestId.fromHeaderValues(
                headerValues == null ? List.of() : Collections.list(headerValues)
        );
    }
}
