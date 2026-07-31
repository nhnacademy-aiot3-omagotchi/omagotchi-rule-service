package site.omagotchi.ruleservice.env;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import site.omagotchi.ruleservice.global.security.TestJwtKeyConfig;

@SpringBootTest
@Slf4j
@Import(TestJwtKeyConfig.class)
@ActiveProfiles("test")
class SpringBootEnvironmentVariableTest {

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
