package site.omagotchi.ruleservice.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * learning-service 전용 RestClient
     * baseUrl이 core.base-url로 고정되어 있어, learning-service 하나만 호출하는 용도로 사용
     * (예: RuleSyncClient의 룰 동기화)
     */
    @Bean
    public RestClient restClient(@Value("${core.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 엔진 간 REST 폴링 전용 RestClient
     * baseUrl을 고정하지 않음 - 매번 다른 피어(engine-a, engine-b, ...)의 주소로 호출해야 하고, 그 주소는 Eureka에서 그때그때 조회해서 얻기 때문
     * connect/read 타임아웃을 각각 2초로 명시 - 이 값은 learning-service 호출과 무관하게, 엔진 생존 판정 폴링 주기(3초)보다 짧아야 한다는 distributed feature 자체의 요구사항에서 나온
     */
    @Bean
    public RestClient engineInternalRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000); // 연결 타임아웃 2초
        factory.setReadTimeout(2_000); // 읽기 타임아웃 2초

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
