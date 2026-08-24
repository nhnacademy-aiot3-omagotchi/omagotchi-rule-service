package site.omagotchi.ruleservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import site.omagotchi.ruleservice.global.security.TestJwtKeyConfig;

@Import(TestJwtKeyConfig.class)
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "learning.base-url=temp",
        "engine.id=test-engine",
        "engine.priority=1"
})
class RuleServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
