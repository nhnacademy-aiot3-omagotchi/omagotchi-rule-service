package site.omagotchi.ruleservice.distributed.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;
import site.omagotchi.ruleservice.global.security.InternalAuthHeader;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineRestClientConfigTest {

    @Test
    @DisplayName("엔진 간 호출 Client의 공유 Secret·Request ID 설정")
    void configuresEngineInternalClient() {
        RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_SELF);
        RestClient expectedClient = mock(RestClient.class);
        when(builder.build()).thenReturn(expectedClient);

        RestClient actualClient = new EngineRestClientConfig()
                .engineInternalRestClient("test-shared-secret", builder);

        then(actualClient).isSameAs(expectedClient);
        verify(builder).requestFactory(isA(SimpleClientHttpRequestFactory.class));
        verify(builder).defaultHeader(InternalAuthHeader.NAME, "test-shared-secret");
        verify(builder).requestInterceptor(isA(RequestIdRestClientInterceptor.class));
    }
}
