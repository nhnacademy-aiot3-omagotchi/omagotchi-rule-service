package site.omagotchi.ruleservice.env;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bean 컴포넌트 스캔이 없는 최소 설정 클래스를 테스트 안에서 만들어서 classes로 지정
 * application.yaml/.env 로딩은 그대로 됨
 * classes를 지정하면 그 지정한 클래스가 컴포넌트 스캔 시작점이 됨
 * MinimalTestConfig에는 @ComponentScan이 없으니 실제 Bean이 하나도 안 딸려옴
 * 반면 application.yaml 로딩은 classes와 무관하게 일어나므로 환경변수 읽는지 테스트하는 목적에 적합하다고 판단
 */
@SpringBootTest(classes = SpringBootEnvironmentVariableTest.MinimalTestConfig.class)
@Slf4j
@ActiveProfiles("local")
class SpringBootEnvironmentVariableTest {

    @SpringBootConfiguration
    static class MinimalTestConfig {

    }

    @Autowired
    Environment environment;

    @Test
    @DisplayName("환경변수 읽는지 확인하는 테스트")
    void envVarTest() {

        String result = this.environment.getProperty("server.port");
        log.info("server.port: {}", result);

        Assertions.assertNotNull(result);
    }
}