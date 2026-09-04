package site.omagotchi.ruleservice.rule.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;

import java.nio.charset.StandardCharsets;

/** Learning의 Rule 조회 API 호출 설정. */
@Configuration
public class LearningRestClientConfig {

    @Bean
    public RestClient learningRestClient(
            LearningClientProperties properties,
            RestClient.Builder builder
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(3_000);

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(
                        properties.username(),
                        properties.password(),
                        StandardCharsets.UTF_8
                ))
                .requestInterceptor(new RequestIdRestClientInterceptor())
                .build();
    }
}
