package site.omagotchi.ruleservice.rule.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import site.omagotchi.ruleservice.rule.domain.Operator;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRuleCacheTest {
    InMemoryRuleCache inMemoryRuleCache = new InMemoryRuleCache();

    @Test
    @DisplayName("룰 캐싱 테스트")
    void applyTest(){
        ThresholdRule thresholdRule = getThresholdRule();

        assertThrows(IllegalArgumentException.class, () -> inMemoryRuleCache.apply(null));

        boolean applied = inMemoryRuleCache.apply(thresholdRule);
        assertTrue(applied);


    }

    @Test
    @DisplayName("예전 룰이 나중에 들어왔을때 캐싱 하지않음")
    void applyWhenOldThresholdRuleTest(){
        ThresholdRule newThresholdRule = getThresholdRule(); // 버전 2L (더 최신)
        ThresholdRule oldThresholdRule = new ThresholdRule( // 버전 1L (더 예전)
                1L,
                "test-eui",
                "temperature",
                Operator.GT,
                20.0,
                1L,
                System.currentTimeMillis()
        );

        boolean appliedNewer = inMemoryRuleCache.apply(newThresholdRule);
        boolean appliedOlder = inMemoryRuleCache.apply(oldThresholdRule);

        assertTrue(appliedNewer);
        assertFalse(appliedOlder);
    }


    @Test
    @DisplayName("룰 평가 테스트")
    void evaluateTest(){
        ThresholdRule thresholdRule = getThresholdRule();

        inMemoryRuleCache.apply(thresholdRule);
        Optional<ThresholdRule> evaluated = inMemoryRuleCache.evaluate("test-eui", "temperature", 30.0);

        assertFalse(evaluated.isEmpty());
        assertEquals(thresholdRule, evaluated.get());
    }
    @Test
    @DisplayName("룰 동기화 테스트 - 전체를 교체. 교체된게있다면 변경사항이있다는것")
    void replaceAllTest(){

        // 캐시 세팅 아이디가 3인 룰만 정상적으로 들어감
        // 나머지는 같은 버전이 연속으로 들어가고있으므로 실패
        inMemoryRuleCache.apply(new ThresholdRule(1L, "test-eui1", "temperature", Operator.GT, 30.0,2L ,System.currentTimeMillis()));
        inMemoryRuleCache.apply(new ThresholdRule(2L, "test-eui2", "temperature", Operator.GT, 30.0,2L ,System.currentTimeMillis()));
        inMemoryRuleCache.apply(new ThresholdRule(3L, "test-eui3", "temperature", Operator.GT, 30.0,1L ,System.currentTimeMillis()));

        List<ThresholdRule> inputs = new ArrayList<>();
        inputs.add(new ThresholdRule(1L, "test-eui1", "temperature", Operator.GT, 20.0,2L ,System.currentTimeMillis()));
        inputs.add(new ThresholdRule(2L, "test-eui2", "temperature", Operator.GT, 20.0,2L ,System.currentTimeMillis()));
        inputs.add(new ThresholdRule(3L, "test-eui3", "temperature", Operator.GT, 20.0,2L ,System.currentTimeMillis()));

        int count = inMemoryRuleCache.replaceAll(inputs);

        assertEquals(1, count); // 하나만 갱신됨
    }


    private ThresholdRule getThresholdRule(){
        return new ThresholdRule(
                1L,
                "test-eui",
                "temperature",
                Operator.GT,
                20.0,
                2L,
                System.currentTimeMillis()
        );
    }
}