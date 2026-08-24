package site.omagotchi.ruleservice.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.security.InternalAuthHeader;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class RestClientConfigTest {

    private static final String SHARED_SECRET = "test-shared-secret";
    private static final String LEARNING_USERNAME = "rule-service";
    private static final String LEARNING_PASSWORD = "test-only-rule-learning-password";

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

    @Test
    @DisplayName("Learning 임계치 기준 조회에는 Rule Credential이 항상 붙는다")
    void attachesLearningCredentialToEveryRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LearningClientProperties properties = new LearningClientProperties(
                "http://learning-service:8080",
                LEARNING_USERNAME,
                LEARNING_PASSWORD
        );
        RestClient restClient = RestClientConfig.applyLearningAuth(builder, properties).build();

        server.expect(requestTo("http://learning-service:8080/api/v1/internal/threshold-rules"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(
                        HttpHeaders.AUTHORIZATION,
                        "Basic " + HttpHeaders.encodeBasicAuth(
                                LEARNING_USERNAME,
                                LEARNING_PASSWORD,
                                StandardCharsets.UTF_8
                        )
                ))
                .andRespond(withNoContent());

        restClient.get()
                .uri("http://learning-service:8080/api/v1/internal/threshold-rules")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }
}
