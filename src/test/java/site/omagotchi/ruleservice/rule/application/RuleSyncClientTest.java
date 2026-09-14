package site.omagotchi.ruleservice.rule.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestId;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;
import site.omagotchi.ruleservice.rule.infrastructure.InMemoryRuleCache;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RuleSyncClientTest {

    private static final String V1 = """
        [{"ruleId":1,"deviceEui":"eui-1","metric":"co2","operator":"GT",
          "threshold":1000.0,"ruleVersion":1,"updatedAt":0}]
        """;
    private static final String V2 = """
        [{"ruleId":1,"deviceEui":"eui-1","metric":"co2","operator":"GT",
          "threshold":800.0,"ruleVersion":2,"updatedAt":0}]
        """;

    MockRestServiceServer restServiceServer;
    InMemoryRuleCache inMemoryRuleCache;
    MeterRegistry meterRegistry;
    RuleSyncClient ruleSyncClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        restServiceServer = MockRestServiceServer.bindTo(builder).build();
        inMemoryRuleCache = new InMemoryRuleCache();
        meterRegistry = new SimpleMeterRegistry();
        ruleSyncClient = new RuleSyncClient(
                builder.requestInterceptor(new RequestIdRestClientInterceptor()).build(),
                inMemoryRuleCache,
                meterRegistry
        );
    }

    @Test
    @DisplayName("룰 엔진 기동 시 Learning의 Rule을 Cache에 적재")
    void loadsRulesOnStartup() throws InterruptedException {
        restServiceServer.expect(requestTo("/api/v1/internal/threshold-rules"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestId.HEADER_NAME, matchesPattern("^[0-9a-f]{32}$")))
                .andRespond(withSuccess(V1, MediaType.APPLICATION_JSON));

        ruleSyncClient.onStartUp();

        long deadline = System.currentTimeMillis() + 3_000;
        while (inMemoryRuleCache.getAll().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertTrue(inMemoryRuleCache.evaluate("eui-1", "co2", 1100.0).isPresent());
        assertFalse(inMemoryRuleCache.getAll().isEmpty());
    }

    @Test
    @DisplayName("재동기화로 누락된 Rule을 보정하면 Counter 증가")
    void incrementsCounterWhenResyncCorrectsMissedRule() throws InterruptedException {
        restServiceServer.expect(requestTo("/api/v1/internal/threshold-rules"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestId.HEADER_NAME, matchesPattern("^[0-9a-f]{32}$")))
                .andRespond(withSuccess(V1, MediaType.APPLICATION_JSON));

        restServiceServer.expect(requestTo("/api/v1/internal/threshold-rules"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestId.HEADER_NAME, matchesPattern("^[0-9a-f]{32}$")))
                .andRespond(withSuccess(V2, MediaType.APPLICATION_JSON));

        ruleSyncClient.onStartUp();

        long deadline = System.currentTimeMillis() + 3_000;
        while (inMemoryRuleCache.getAll().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        ruleSyncClient.reSync();

        assertFalse(inMemoryRuleCache.getAll().isEmpty());
        assertTrue(inMemoryRuleCache.evaluate("eui-1", "co2", 900.0).isPresent());
        assertEquals(1.0, meterRegistry.get("rule.sync.missed").counter().count());
    }
}
