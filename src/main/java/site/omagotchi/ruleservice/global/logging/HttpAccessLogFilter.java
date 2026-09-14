package site.omagotchi.ruleservice.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Servlet Filter 종료 시점의 HTTP 요청별 접근 이벤트 기록.
 *
 * <ul>
 *     <li>제외 대상: 생존 확인 요청</li>
 *     <li>경로 표현: 원본 URI 대신 Spring MVC 라우트 템플릿</li>
 *     <li>오류 표현: 예외 메시지·스택 트레이스를 제외한 상태</li>
 * </ul>
 */
@Slf4j
@Component
    // Request ID 필터와 Spring HTTP 서버 관측 다음의 Trace Context 사용
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class HttpAccessLogFilter extends OncePerRequestFilter {

    private static final String DATASET = "rule-service.http";
    private static final String ACTION = "http.server.request.completed";

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        // 주기적인 생존 확인·메트릭 수집의 접근 이벤트 제외
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/prometheus")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 요청 종료 뒤 Span 접근이 불가능한 경우를 위한 현재 Trace 식별자 보존
        Span currentSpan = this.tracer.currentSpan();
        String traceId = currentSpan == null ? null : currentSpan.context().traceId();
        String spanId = currentSpan == null ? null : currentSpan.context().spanId();
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            writeAccessEvent(request, response, failure, traceId, spanId, startedAtNanos);
        }
    }

    private static void writeAccessEvent(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable failure,
            String traceId,
            String spanId,
            long startedAtNanos
    ) {
        int statusCode = failure != null && !response.isCommitted()
                ? 500
                : response.getStatus();
        LoggingEventBuilder event = statusCode >= 500 ? log.atError() : log.atInfo();
        event = event.addKeyValue("event.dataset", DATASET)
                .addKeyValue("event.action", ACTION)
                .addKeyValue("event.outcome", failure != null || statusCode >= 400 ? "failure" : "success")
                .addKeyValue("event.duration", Math.max(0L, System.nanoTime() - startedAtNanos))
                .addKeyValue("http.request.method", request.getMethod())
                .addKeyValue("http.response.status_code", statusCode);

        // 사용자 입력 URI 대신 정해진 Spring MVC 라우트 템플릿 기록
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (route != null) {
            event = event.addKeyValue("omagotchi.http.route", route.toString());
        }
        if (traceId != null) {
            event = event.addKeyValue("trace.id", traceId);
        }
        if (spanId != null) {
            event = event.addKeyValue("span.id", spanId);
        }
        event.log("HTTP request completed");
    }
}
