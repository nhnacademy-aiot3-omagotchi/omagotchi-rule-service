package site.omagotchi.ruleservice.distributed.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;
import site.omagotchi.ruleservice.global.security.InternalAuthHeader;

/** Rule 엔진 간 내부 API 호출 설정. */
@Configuration
public class EngineRestClientConfig {

    @Bean
    public RestClient engineInternalRestClient(
            @Value("${internal.shared-secret}") String sharedSecret,
            RestClient.Builder builder
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(300);
        requestFactory.setReadTimeout(500);

        return builder
                .requestFactory(requestFactory)
                .defaultHeader(InternalAuthHeader.NAME, sharedSecret)
                .requestInterceptor(new RequestIdRestClientInterceptor())
                .build();
    }
}
