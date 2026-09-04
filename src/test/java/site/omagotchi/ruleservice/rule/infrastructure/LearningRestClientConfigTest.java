package site.omagotchi.ruleservice.rule.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningRestClientConfigTest {

    private static final String USERNAME = "rule-service";
    private static final String PASSWORD = "test-only-rule-learning-password";

    @Test
    @DisplayName("Learning Rule 조회 Client의 주소·Credential·Request ID 설정")
    void configuresLearningRuleClient() {
        RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_SELF);
        RestClient expectedClient = mock(RestClient.class);
        when(builder.build()).thenReturn(expectedClient);
        LearningClientProperties properties = new LearningClientProperties(
                "http://learning-service:8080",
                USERNAME,
                PASSWORD
        );

        RestClient actualClient = new LearningRestClientConfig()
                .learningRestClient(properties, builder);

        then(actualClient).isSameAs(expectedClient);
        verify(builder).baseUrl(properties.baseUrl());
        verify(builder).requestFactory(isA(SimpleClientHttpRequestFactory.class));
        verify(builder).requestInterceptor(isA(RequestIdRestClientInterceptor.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<HttpHeaders>> headersCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(builder).defaultHeaders(headersCaptor.capture());
        HttpHeaders headers = new HttpHeaders();
        headersCaptor.getValue().accept(headers);
        then(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(
                "Basic " + HttpHeaders.encodeBasicAuth(USERNAME, PASSWORD, StandardCharsets.UTF_8)
        );
    }
}
