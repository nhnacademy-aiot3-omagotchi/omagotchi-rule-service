package site.omagotchi.ruleservice.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestIdFilterTest {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private RequestIdFilter requestIdFilter;

    @BeforeEach
    void setUp() {
        requestIdFilter = new RequestIdFilter();
    }

    @Test
    @DisplayName("유효한 X-Request-ID가 들어오면 그대로 이어받아 응답 헤더에 반영한다")
    void reusesValidIncomingRequestIdTest() throws ServletException, IOException {
        String incoming = "abc123-DEF-456";

        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn(incoming);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(REQUEST_ID_HEADER, incoming);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Request-ID가 없으면 새로 발급한다")
    void generatesNewRequestIdWhenHeaderMissingTest() throws ServletException, IOException {
        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn(null);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(eq(REQUEST_ID_HEADER), argThat(id -> Objects.nonNull(id) && !id.isBlank()));
    }

    @Test
    @DisplayName("헤더 값이 빈 문자열이면 새로 발급한다")
    void generatesNewRequestIdWhenHeaderIsBlank() throws ServletException, IOException {
        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn("");

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(eq(REQUEST_ID_HEADER), argThat(id -> !id.isBlank()));
    }

    @Test
    @DisplayName("형식이 이상해도 값이 있으면 그대로 이어받는다 (opaque 문자열 취급)")
    void preservesIncomingRequestIdRegardlessOfFormat() throws ServletException, IOException {
        String unusualButPresent = "line1\nline2";

        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn(unusualButPresent);

        requestIdFilter.doFilter(request, response, filterChain);

        verify(response).setHeader(REQUEST_ID_HEADER, unusualButPresent);
    }

    @Test
    @DisplayName("체인 처리 중에는 MDC에 requestId가 채워져 있다")
    void mdcIsPopulatedDuringChainExecutionTest() throws ServletException, IOException {
        String incoming = "known-request-id";

        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn(incoming);

        doAnswer(invocation -> {
            assertThat(MDC.get(MDC_REQUEST_ID_KEY)).isEqualTo(incoming);
            return null;
        }).when(filterChain).doFilter(request, response);

        requestIdFilter.doFilter(request, response, filterChain);
    }

    @Test
    @DisplayName("요청 처리가 끝나면 MDC에서 requestId가 제거된다")
    void removesMdcAfterRequestCompletesTest() throws ServletException, IOException {
        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn(null);

        requestIdFilter.doFilter(request, response, filterChain);

        assertThat(MDC.get(MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("필터 체인 도중 예외가 발생해도 MDC는 정리된다")
    void removesMdcEvenWhenChainThrowsTest() throws ServletException, IOException {
        when(request.getHeader(REQUEST_ID_HEADER)).thenReturn(null);
        doThrow(new ServletException("체인 실패")).when(filterChain).doFilter(request, response);

        assertThrows(ServletException.class, () -> requestIdFilter.doFilter(request, response, filterChain));

        assertThat(MDC.get(MDC_REQUEST_ID_KEY)).isNull();
    }
}