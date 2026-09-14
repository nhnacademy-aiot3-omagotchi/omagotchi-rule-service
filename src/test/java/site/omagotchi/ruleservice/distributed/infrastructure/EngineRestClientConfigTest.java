package site.omagotchi.ruleservice.distributed.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestId;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;
import site.omagotchi.ruleservice.global.security.InternalAuthHeader;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class EngineRestClientConfigTest {

    @Test
    @DisplayName("엔진 간 호출 Client의 실제 설정을 통한 인증·Request ID 전파")
    void propagatesCredentialAndCurrentRequestId() {
        // Given
        String requestId = "Dev-Request_01.test";
        RestClient configured = new EngineRestClientConfig()
                .engineInternalRestClient("test-shared-secret", RestClient.builder());
        // 실제 설정의 Interceptor·Header는 유지하고 외부 통신만 테스트 대역으로 교체
        RestClient.Builder builder = configured.mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://engine-b:8080/probe"))
                .andExpect(header(InternalAuthHeader.NAME, "test-shared-secret"))
                .andExpect(header(RequestId.HEADER_NAME, requestId))
                .andRespond(withNoContent());

        // When
        try (RequestIdContext.Scope ignored = RequestIdContext.openInbound(new RequestId(requestId))) {
            client.get().uri("http://engine-b:8080/probe").retrieve().toBodilessEntity();
        }

        // Then
        server.verify();
    }
}
