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
import java.util.UUID;

/**
 * 모든 HTTP 요청 진입 시 traceId를 발급해 MDC에 등록
 * MQTT 파이프라인이 메시지 단위로 traceId를 전파하는 것과 동일한 원칙을 HTTP 요청 단위에 적용한 것
 * -> 요청 처리 중 찍히는 모든 로그, GlobalExceptionHandler의 에러 응답이 이 traceId를 공유함
 *
 * 필터 체인의 가장 앞단에 위치해야 함(@Order 최우선 - 가장 먼저 실행됨)
 * -> 인증 등 다른 필터가 나중에 추가되어도 그 실패 응답까지 traceId를 갖도록 보장하기 위함
 *
 * 스레드풀 재사용 시 MDC 값이 다음 요청으로 새어나가는 것을 막기 위해 finally에서 제거
 */
@Component
@Order(Integer.MIN_VALUE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            MDC.put(MDC_TRACE_ID_KEY, UUID.randomUUID().toString());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }
}