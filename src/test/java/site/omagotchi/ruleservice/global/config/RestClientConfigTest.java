package site.omagotchi.ruleservice.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.security.InternalAuthHeader;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class RestClientConfigTest {

    private static final String SHARED_SECRET = "test-shared-secret";

    @Test
    @DisplayName("엔진 간 내부 호출에는 X-Internal-Token 헤더가 항상 붙는다")
    void attachesInternalTokenHeaderToEveryRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // 프로덕션의 헤더 부착 로직을 그대로 통과시킴 - 누가 defaultHeader를 지우면 여기서 잡힘
        RestClient restClient = RestClientConfig.applyInternalAuth(builder, SHARED_SECRET).build();

        server.expect(requestTo("http://peer-host:8082/api/v1/internal/engines/self"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(InternalAuthHeader.NAME, SHARED_SECRET))
                .andRespond(withNoContent());

        restClient.get()
                .uri("http://peer-host:8082/api/v1/internal/engines/self")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }
}
