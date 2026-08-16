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
    public RestClient restClient(CoreClientProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000); // 연결 타임아웃 1초
        factory.setReadTimeout(3_000); // 읽기 타임아웃 3초 (learning-service API가 행에 걸려도 스케줄러 스레드를 무기한 점유하지 않도록)

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 엔진 간 REST 폴링 전용 RestClient
     * baseUrl을 고정하지 않음 - 매번 다른 피어(engine-a, engine-b, ...)의 주소로 호출해야 하고, 그 주소는 Eureka에서 그때그때 조회해서 얻기 때문
     */
    @Bean
    public RestClient engineInternalRestClient(@Value("${internal.shared-secret}") String sharedSecret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300); // 연결 타임아웃 300ms
        factory.setReadTimeout(500); // 읽기 타임아웃 500ms

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", sharedSecret)
                .build();
    }
}
