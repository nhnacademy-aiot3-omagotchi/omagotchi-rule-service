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
import site.omagotchi.ruleservice.rule.infrastructure.InMemoryRuleCache;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RuleSyncClientTest {

    //응답 json가정 (확정 아님)
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
    void setUp(){
        RestClient.Builder builder = RestClient.builder();
        restServiceServer = MockRestServiceServer.bindTo(builder).build();
        inMemoryRuleCache = new InMemoryRuleCache();
        meterRegistry = new SimpleMeterRegistry();
        ruleSyncClient = new RuleSyncClient(builder.build(), inMemoryRuleCache, meterRegistry);
    }


    @Test
    @DisplayName("룰 엔진 기동 시 캐시 적재 테스트")
    void initialTest() throws InterruptedException{

        //현재 learning Service에 룰엔진 관련 코드가 없음. 다음과 같이 가짜 요청/응답으로 테스트 진행
        restServiceServer.expect(requestTo("/api/rules"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(V1, MediaType.APPLICATION_JSON));


        ruleSyncClient.onStartUp(); // 기동됐다고 가정. 원래는 ApplicationReadyEvent로 자동 실행됨.

        long deadline = System.currentTimeMillis() + 3000; // 3초 대기
        while (inMemoryRuleCache.getAll().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(inMemoryRuleCache.evaluate("eui-1", "co2", 1100.0).isPresent()); // V1기준 룰 히트됨
        assertFalse(inMemoryRuleCache.getAll().isEmpty());

    }

    @Test
    @DisplayName("재동기화 보정 테스트 - 들어와야하게 안들어오다가 재동기때 들어온경우 카운터 증가")
    void reSyncTest() throws InterruptedException{
        restServiceServer.expect(requestTo("/api/rules"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(V1, MediaType.APPLICATION_JSON));

        restServiceServer.expect(requestTo("/api/rules"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(V2, MediaType.APPLICATION_JSON));

        ruleSyncClient.onStartUp();

        long deadline = System.currentTimeMillis() + 3000; // 3초 대기
        while (inMemoryRuleCache.getAll().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        ruleSyncClient.reSync();

        assertFalse(inMemoryRuleCache.getAll().isEmpty());
        assertTrue(inMemoryRuleCache.evaluate("eui-1", "co2", 900.0).isPresent()); //V1 -> V2 전환됨. 룰히트됨.
        assertEquals(1.0, meterRegistry.get("rule.sync.missed").counter().count());
    }




}