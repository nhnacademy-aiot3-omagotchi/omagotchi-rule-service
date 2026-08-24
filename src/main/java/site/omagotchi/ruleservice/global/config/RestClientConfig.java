package site.omagotchi.ruleservice.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.security.InternalAuthHeader;

import java.nio.charset.StandardCharsets;

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

        return applyLearningAuth(
                RestClient.builder()
                        .baseUrl(properties.baseUrl())
                        .requestFactory(factory),
                properties
        ).build();
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

        return applyInternalAuth(
                RestClient.builder().requestFactory(factory),
                sharedSecret
        ).build();
    }

    /**
     * 내부 API 호출에 공유 시크릿 헤더를 붙임
     * 이 부착이 빠지면 모든 피어 폴링이 403이 되어 상호 AUTH_FAILED로 빠지므로, 테스트가 이 계약을 직접 고정할 수 있도록 분리해둠
     * RequestFactory 설정은 일부러 여기 넣지 않음 - 테스트가 MockRestServiceServer를 바인딩할 때 호출 순서에 의존하지 않게 하기 위함
     */
    public static RestClient.Builder applyInternalAuth(RestClient.Builder builder, String sharedSecret) {
        return builder.defaultHeader(InternalAuthHeader.NAME, sharedSecret);
    }

    /**
     * Learning 임계치 기준 조회에 Rule–Learning 관계 전용 Basic Credential을 부착.
     */
    public static RestClient.Builder applyLearningAuth(
            RestClient.Builder builder,
            CoreClientProperties properties
    ) {
        return builder.defaultHeaders(headers -> headers.setBasicAuth(
                properties.username(),
                properties.password(),
                StandardCharsets.UTF_8
        ));
    }
}
