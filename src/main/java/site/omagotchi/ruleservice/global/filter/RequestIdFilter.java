package site.omagotchi.ruleservice.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모든 HTTP 요청 진입 시 requestId를 확보해 MDC에 등록
 * Nginx가 발급한 X-Request-ID를 이어받되(신뢰 경계는 Nginx), 없거나 형식이 안전하지 않으면 새로 발급
 * -> 요청 처리 중 찍히는 모든 로그, GlobalExceptionHandler의 에러 응답, 응답 헤더가 이 값을 공유함
 * <p>
 * 필터 체인의 가장 앞단에 위치해야 함 (@Order 최우선 - 가장 먼저 실행됨)
 * 스레드풀 재사용 시 MDC 값이 다른 요청으로 새어나가는 것을 막기 위해 finally에서 제거
 * <p>
 * 파이프라인(Message)의 traceId와는 별개의 값임
 * - 이 requestId는 HTTP 요청 하나를 추적하고,
 * - traceId는 MQTT 메시지 하나가 파이프라인을 통과하는 여정을 추적함
 */
@Component
@Order(Integer.MIN_VALUE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    // Nginx의 $request_id(32자리 hex)와 UUID(하이픈 포함 36자) 둘 다 허용하되, 그 외 임의 문자는 신뢰 X
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[a-zA-Z0-9-]{1,100}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = this.resolveRequestId(request);

        try {
            MDC.put(MDC_REQUEST_ID_KEY, requestId);
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }

    // 신뢰 경계는 Nginx - 그 경계를 거치지 않은 요청이 섞일 가능성을 배제할 수 없어 형식을 방어적으로 검증
    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);

        if (Objects.nonNull(incoming) && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }

        // 없거나, 형식이 안전하지 않으면 새로 발급
        return UUID.randomUUID().toString();
    }
}