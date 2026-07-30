package site.omagotchi.ruleservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "core.base-url=temp",
        "engine.id=test-engine",
        "engine.priority=1"
})
class RuleServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}