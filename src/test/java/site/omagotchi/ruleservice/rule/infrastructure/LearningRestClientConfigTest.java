package site.omagotchi.ruleservice.rule.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestId;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;

import java.nio.charset.StandardCharsets;
import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class LearningRestClientConfigTest {

    @Test
    @DisplayName("Discovery 주소의 실제 선택 인스턴스 호출과 인증·Request ID 유지")
    void routesThroughLoadBalancer() throws Exception {
        // Given
        LoadBalancerClient loadBalancer = mock(LoadBalancerClient.class);
        DefaultServiceInstance instance = new DefaultServiceInstance(
                "learning-b", "learning-service", "127.0.0.1", 18080, false);
        when(loadBalancer.execute(eq("learning-service"), any(LoadBalancerRequest.class)))
                .thenAnswer(invocation -> {
                    LoadBalancerRequest<ClientHttpResponse> request = invocation.getArgument(1);
                    return request.apply(instance);
                });
        when(loadBalancer.reconstructURI(eq(instance), any(URI.class)))
                .thenAnswer(invocation -> instance.getUri().resolve(((URI) invocation.getArgument(1)).getRawPath()));
        LearningClientProperties properties = new LearningClientProperties(
                "lb://learning-service", "rule-service", "test-only-password");
        RestClient.Builder builder = new LearningRestClientConfig()
                .learningRestClient(properties, RestClient.builder(), new LoadBalancerInterceptor(loadBalancer))
                .mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://127.0.0.1:18080/probe"))
                .andExpect(header(RequestId.HEADER_NAME, "deployment-check"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + HttpHeaders.encodeBasicAuth(
                        properties.username(), properties.password(), StandardCharsets.UTF_8)))
                .andRespond(withNoContent());

        // When
        try (RequestIdContext.Scope ignored = RequestIdContext.openInbound(new RequestId("deployment-check"))) {
            client.get().uri("/probe").retrieve().toBodilessEntity();
        }

        // Then
        server.verify();
    }

    @Test
    @DisplayName("Learning Rule 조회 Client의 실제 설정을 통한 인증·Request ID 전파")
    void propagatesCredentialAndCurrentRequestId() {
        // Given
        String requestId = "Dev-Request_01.test";
        LearningClientProperties properties = new LearningClientProperties(
                "http://learning-service:8080",
                "rule-service",
                "test-only-rule-learning-password"
        );
        RestClient configured = new LearningRestClientConfig()
                .learningRestClient(properties, RestClient.builder(), null);
        // 실제 설정의 Interceptor·Header는 유지하고 외부 통신만 테스트 대역으로 교체
        RestClient.Builder builder = configured.mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://learning-service:8080/probe"))
                .andExpect(header(HttpHeaders.AUTHORIZATION,
                        "Basic " + HttpHeaders.encodeBasicAuth(
                                properties.username(), properties.password(), StandardCharsets.UTF_8)))
                .andExpect(header(RequestId.HEADER_NAME, requestId))
                .andRespond(withNoContent());

        // When
        try (RequestIdContext.Scope ignored = RequestIdContext.openInbound(new RequestId(requestId))) {
            client.get().uri("http://learning-service:8080/probe").retrieve().toBodilessEntity();
        }

        // Then
        server.verify();
    }
}
