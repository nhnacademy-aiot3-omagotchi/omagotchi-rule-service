package site.omagotchi.ruleservice.rule.infrastructure;

import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;

import java.nio.charset.StandardCharsets;

/** Learning의 Rule 조회 API 호출 설정. */
@Configuration
@LoadBalancerClient(name = "learning-service", configuration = LearningLoadBalancerConfig.class)
public class LearningRestClientConfig {

    @Bean
    public RestClient learningRestClient(
            LearningClientProperties properties,
            RestClient.Builder builder,
            LoadBalancerInterceptor loadBalancerInterceptor
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1_000);
        requestFactory.setReadTimeout(3_000);

        // 운영 Discovery 주소만 분산 호출, 로컬의 명시적 HTTP 주소는 그대로 사용.
        if (properties.baseUrl().startsWith("lb://")) {
            builder.requestInterceptor(loadBalancerInterceptor);
        }

        return builder
                .baseUrl(properties.baseUrl().replaceFirst("^lb:", "http:"))
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
