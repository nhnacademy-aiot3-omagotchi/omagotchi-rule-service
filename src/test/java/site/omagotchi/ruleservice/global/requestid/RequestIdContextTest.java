package site.omagotchi.ruleservice.global.requestid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("새 Scope는 유효한 Request ID를 만들고 종료 시 바깥 값을 복원")
    void createsRequestIdAndRestoresOuterContext() {
        String outerRequestId = "0123456789abcdef0123456789abcdef";
        MDC.put(RequestIdContext.MDC_KEY, outerRequestId);

        try (RequestIdContext.Scope ignored = RequestIdContext.openNew()) {
            assertThat(MDC.get(RequestIdContext.MDC_KEY))
                    .matches("^[0-9a-f]{32}$")
                    .isNotEqualTo(outerRequestId);
        }

        assertThat(MDC.get(RequestIdContext.MDC_KEY)).isEqualTo(outerRequestId);
    }

    @Test
    @DisplayName("HTTP 진입 Scope는 종료 시 이전 Thread의 Request ID를 되살리지 않음")
    void inboundScopeRemovesStaleOuterContext() {
        MDC.put(RequestIdContext.MDC_KEY, "0123456789abcdef0123456789abcdef");

        try (RequestIdContext.Scope ignored = RequestIdContext.openInbound(
                new RequestId("abcdef0123456789abcdef0123456789")
        )) {
            assertThat(MDC.get(RequestIdContext.MDC_KEY))
                    .isEqualTo("abcdef0123456789abcdef0123456789");
        }

        assertThat(MDC.get(RequestIdContext.MDC_KEY)).isNull();
    }
}
