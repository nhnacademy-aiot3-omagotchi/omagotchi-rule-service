package site.omagotchi.ruleservice.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InternalServiceAuthFilterTest {

    private static final String SHARED_SECRET = "test-shared-secret";

    @Mock
    private FilterChain filterChain;

    private InternalServiceAuthFilter internalServiceAuthFilter;

    @BeforeEach
    void setUp() {
        InternalAuthProperties internalAuthProperties = new InternalAuthProperties(SHARED_SECRET);
        this.internalServiceAuthFilter = new InternalServiceAuthFilter(
                internalAuthProperties,
                new ObjectMapper(),
                PathPatternRequestMatcher.withDefaults().matcher("/api/v1/internal/**")
        );
    }

    @Test
    @DisplayName("올바른 시크릿 헤더면 체인을 통과시킨다")
    void passesThroughWithCorrectSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/internal/engines/self");
        request.addHeader(InternalAuthHeader.NAME, SHARED_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.internalServiceAuthFilter.doFilter(request, response, this.filterChain);

        verify(this.filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("헤더가 없으면 403과 함께 체인을 막는다")
    void rejectsWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/internal/engines/self");
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.internalServiceAuthFilter.doFilter(request, response, this.filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains(SecurityErrorCode.ACCESS_DENIED.code());
        verify(this.filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("시크릿 값이 틀리면 403과 함께 체인을 막는다")
    void rejectsWhenSecretMismatches() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/flows/flow-1/start");
        request.addHeader(InternalAuthHeader.NAME, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.internalServiceAuthFilter.doFilter(request, response, this.filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(this.filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("거부 응답의 path 필드에 요청 URI가 그대로 담긴다")
    void rejectionResponseIncludesRequestPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/internal/engines/self");
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.internalServiceAuthFilter.doFilter(request, response, this.filterChain);

        assertThat(response.getContentAsString()).contains("/api/v1/internal/engines/self");
    }

    @Test
    @DisplayName("대상 경로가 아니면 시크릿 검증 없이 체인을 통과시킨다 (shouldNotFilter)")
    void bypassesValidationForUnrelatedPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rules");
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.internalServiceAuthFilter.doFilter(request, response, this.filterChain);

        verify(this.filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("Context Path가 있어도 내부 API Credential을 검증한다")
    void validatesInternalPathWithContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/rule/api/v1/internal/engines/self"
        );
        request.setContextPath("/rule");
        MockHttpServletResponse response = new MockHttpServletResponse();

        this.internalServiceAuthFilter.doFilter(request, response, this.filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(this.filterChain, never()).doFilter(request, response);
    }
}
