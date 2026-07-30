package site.omagotchi.ruleservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import site.omagotchi.ruleservice.global.security.TestJwtKeyConfig;

@SpringBootTest
@Import(TestJwtKeyConfig.class)
@ActiveProfiles("test")
class RuleServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
