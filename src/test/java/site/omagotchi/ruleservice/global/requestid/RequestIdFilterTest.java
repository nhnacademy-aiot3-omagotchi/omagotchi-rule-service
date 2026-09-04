package site.omagotchi.ruleservice.global.requestid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestIdFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private final RequestIdFilter requestIdFilter = new RequestIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("유효한 X-Request-ID가 들어오면 그대로 이어받아 응답 헤더에 반영한다")
    void reusesValidIncomingRequestId() throws ServletException, IOException {
        String incoming = "0123456789abcdef0123456789abcdef";

        givenRequestIds(incoming);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(RequestId.HEADER_NAME, incoming);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Request-ID가 없으면 새로 발급한다")
    void generatesNewRequestIdWhenHeaderMissing() throws ServletException, IOException {
        givenRequestIds();

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(
                eq(RequestId.HEADER_NAME),
                argThat(RequestId::isValid)
        );
    }

    @Test
    @DisplayName("헤더 값이 빈 문자열이면 새로 발급한다")
    void generatesNewRequestIdWhenHeaderIsBlank() throws ServletException, IOException {
        givenRequestIds(" ");

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(
                eq(RequestId.HEADER_NAME),
                argThat(RequestId::isValid)
        );
    }

    @Test
    @DisplayName("형식이 잘못된 X-Request-ID는 새 값으로 교체한다")
    void replacesInvalidIncomingRequestId() throws ServletException, IOException {
        String invalid = "ABCDEF0123456789ABCDEF0123456789";

        givenRequestIds(invalid);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(
                eq(RequestId.HEADER_NAME),
                argThat(id -> RequestId.isValid(id) && !invalid.equals(id))
        );
    }

    @Test
    @DisplayName("개행 문자가 포함된 X-Request-ID는 응답에 반사하지 않고 새 값으로 교체한다")
    void replacesCrlfLikeIncomingRequestId() throws ServletException, IOException {
        String crlfLike = "0123456789abcdef\r\nX-Injected: 1";

        givenRequestIds(crlfLike);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(
                eq(RequestId.HEADER_NAME),
                argThat(id -> RequestId.isValid(id) && !crlfLike.equals(id))
        );
    }

    @Test
    @DisplayName("유효한 X-Request-ID가 두 개 들어와도 둘 다 신뢰하지 않고 새 값으로 교체한다")
    void replacesDuplicateValidIncomingRequestIds() throws ServletException, IOException {
        String first = "0123456789abcdef0123456789abcdef";
        String second = "abcdef0123456789abcdef0123456789";
        givenRequestIds(first, second);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(
                eq(RequestId.HEADER_NAME),
                argThat(id -> RequestId.isValid(id)
                        && !first.equals(id)
                        && !second.equals(id))
        );
    }

    @Test
    @DisplayName("체인 처리 중에는 MDC에 requestId가 채워져 있다")
    void populatesMdcDuringChainExecution() throws ServletException, IOException {
        String incoming = "abcdef0123456789abcdef0123456789";

        givenRequestIds(incoming);

        doAnswer(invocation -> {
            assertThat(MDC.get(RequestIdContext.MDC_KEY)).isEqualTo(incoming);
            return null;
        }).when(filterChain).doFilter(request, response);

        requestIdFilter.doFilter(request, response, filterChain);
    }

    @Test
    @DisplayName("요청 처리가 끝나면 MDC에서 requestId가 제거된다")
    void removesMdcAfterRequestCompletes() throws ServletException, IOException {
        MDC.put(RequestIdContext.MDC_KEY, "abcdef0123456789abcdef0123456789");
        givenRequestIds();

        requestIdFilter.doFilter(request, response, filterChain);

        assertThat(MDC.get(RequestIdContext.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("필터 체인 도중 예외가 발생해도 MDC는 정리된다")
    void removesMdcEvenWhenChainThrows() throws ServletException, IOException {
        MDC.put(RequestIdContext.MDC_KEY, "abcdef0123456789abcdef0123456789");
        givenRequestIds();
        doThrow(new ServletException("체인 실패")).when(filterChain).doFilter(request, response);

        assertThrows(ServletException.class, () -> requestIdFilter.doFilter(request, response, filterChain));

        assertThat(MDC.get(RequestIdContext.MDC_KEY)).isNull();
    }

    private void givenRequestIds(String... requestIds) {
        when(request.getHeaders(RequestId.HEADER_NAME))
                .thenReturn(Collections.enumeration(List.of(requestIds)));
    }
}
