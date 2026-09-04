package site.omagotchi.ruleservice.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

/**
 * Rule HTTP 내부 오류의 중앙 검색용 이벤트와 로컬 진단 이벤트 기록.
 *
 * <ul>
 *     <li>오류 이벤트: 중앙 검색과 Telegram 알림에 필요한 안전한 필드</li>
 *     <li>진단 이벤트: 같은 {@code event.id}로 연결된 원본 예외와 스택 트레이스</li>
 *     <li>Request ID: {@code RequestIdFilter}의 MDC 값을 구조화 인코더에서 기록</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleErrorEventLogger {

    private final Tracer tracer;

    void logUnexpected(
            Exception exception,
            int statusCode,
            HttpServletRequest request
    ) {
        // 중앙 오류 이벤트와 로컬 진단 이벤트의 연결 식별자
        String eventId = UUID.randomUUID().toString();
        Span currentSpan = this.tracer.currentSpan();

        // 원본 예외 내용을 제외한 중앙 수집·알림용 이벤트
        LoggingEventBuilder errorEvent = log.atError()
                .addKeyValue("event.id", eventId)
                .addKeyValue("event.dataset", "rule-service.error")
                .addKeyValue("event.action", "http.server.request.failed")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("error.code", CommonErrorCode.INTERNAL_SERVER_ERROR.code())
                .addKeyValue("error.type", exception.getClass().getName())
                .addKeyValue("http.request.method", request.getMethod())
                .addKeyValue("http.response.status_code", statusCode);

        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (route != null) {
            errorEvent = errorEvent.addKeyValue("omagotchi.http.route", route.toString());
        }
        errorEvent = addTraceFields(errorEvent, currentSpan);
        errorEvent.log("Unexpected server error");

        // 서버 직접 진단용 원본 예외와 스택 트레이스 이벤트
        LoggingEventBuilder diagnosticEvent = log.atError()
                .addKeyValue("event.id", eventId)
                .addKeyValue("event.dataset", "rule-service.diagnostic")
                .addKeyValue("event.action", "http.server.request.failed")
                .addKeyValue("event.outcome", "failure");
        diagnosticEvent = addTraceFields(diagnosticEvent, currentSpan);
        diagnosticEvent.setCause(exception).log("Unexpected server failure diagnostic");
    }

    private static LoggingEventBuilder addTraceFields(LoggingEventBuilder event, Span span) {
        if (span == null) {
            return event;
        }
        return event
                .addKeyValue("trace.id", span.context().traceId())
                .addKeyValue("span.id", span.context().spanId());
    }
}
