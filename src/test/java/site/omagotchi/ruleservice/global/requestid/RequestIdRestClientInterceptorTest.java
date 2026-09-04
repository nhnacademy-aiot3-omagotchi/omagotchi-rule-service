package site.omagotchi.ruleservice.global.requestid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class RequestIdRestClientInterceptorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("현재 요청의 Request ID 전파")
    void propagatesCurrentRequestId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .requestInterceptor(new RequestIdRestClientInterceptor())
                .build();
        String requestId = "0123456789abcdef0123456789abcdef";
        MDC.put(RequestIdContext.MDC_KEY, requestId);
        server.expect(requestTo("http://learning-service.test/probe"))
                .andExpect(header(RequestId.HEADER_NAME, requestId))
                .andRespond(withNoContent());

        client.get().uri("http://learning-service.test/probe").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("잘못된 MDC 값의 신규 Request ID 교체")
    void replacesInvalidMdcValue() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .requestInterceptor(new RequestIdRestClientInterceptor())
                .build();
        MDC.put(RequestIdContext.MDC_KEY, "invalid-request-id");
        server.expect(requestTo("http://learning-service.test/probe"))
                .andExpect(header(RequestId.HEADER_NAME, matchesPattern("^[0-9a-f]{32}$")))
                .andRespond(withNoContent());

        client.get().uri("http://learning-service.test/probe").retrieve().toBodilessEntity();

        server.verify();
    }
}
