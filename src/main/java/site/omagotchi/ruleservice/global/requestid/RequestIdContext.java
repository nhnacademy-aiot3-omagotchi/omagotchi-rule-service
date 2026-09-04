package site.omagotchi.ruleservice.global.requestid;

import org.slf4j.MDC;

/** Request ID의 MDC 범위 관리. */
public final class RequestIdContext {

    public static final String MDC_KEY = "http.request.id";

    private RequestIdContext() {
    }

    /** 신규 Request ID 범위 생성과 이전 값 복원. */
    public static Scope openNew() {
        String previousRequestId = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, RequestId.generate().value());
        return () -> {
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
        };
    }

    /** HTTP 요청 범위 생성과 종료 시 값 제거. */
    public static Scope openInbound(RequestId requestId) {
        MDC.put(MDC_KEY, requestId.value());
        return () -> MDC.remove(MDC_KEY);
    }

    public static RequestId currentOrGenerate() {
        String current = MDC.get(MDC_KEY);
        return RequestId.isValid(current) ? new RequestId(current) : RequestId.generate();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
