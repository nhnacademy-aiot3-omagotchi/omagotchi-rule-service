package site.omagotchi.ruleservice.rule.application;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import site.omagotchi.ruleservice.rule.infrastructure.InMemoryRuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.dto.RuleResponse;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@ActiveProfiles("local")
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
class RuleSyncListenerTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    InMemoryRuleCache inMemoryRuleCache;

    @Autowired
    MeterRegistry meterRegistry;


    @Test
    @DisplayName("정상 룰 수신 - 캐싱 작업")
    void ruleUpdatedSuccessTest() throws InterruptedException {
        rabbitTemplate.convertAndSend(
                RuleSyncListener.RULE_UPDATED_EXCHANGE,
                "",
                new RuleResponse(1L, "eui-1", "co2", "GT", 1000.0, 1L, 0L)
        );

        long deadline = System.currentTimeMillis() + 5000;
        while (inMemoryRuleCache.evaluate("eui-1", "co2", 1500).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertTrue(inMemoryRuleCache.evaluate("eui-1", "co2", 1500).isPresent());
        assertFalse(inMemoryRuleCache.getAll().isEmpty());
    }

    @Test
    @DisplayName("이상 룰 수신 - rejectCounter 증가")
    void ruleUpdatedFailTest() throws InterruptedException {
        double before = meterRegistry.get("rule.sync.rejected").counter().count();
        rabbitTemplate.convertAndSend(
                RuleSyncListener.RULE_UPDATED_EXCHANGE,
                "",
                new RuleResponse(1L, "eui-1", "co2", "말도 안되는 비교연산자", 1000.0, 1L, 0L)
        );

        long deadline = System.currentTimeMillis() + 5000;
        while (meterRegistry.get("rule.sync.rejected").counter().count() < before + 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(before + 1, meterRegistry.get("rule.sync.rejected").counter().count());
        assertTrue(inMemoryRuleCache.evaluate("eui-2", "humidity", 1500).isEmpty());  // 이 룰은 미반영

    }
}