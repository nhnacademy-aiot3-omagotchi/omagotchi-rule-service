package site.omagotchi.ruleservice.rule.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.omagotchi.ruleservice.rule.domain.Operator;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.domain.RuleCache;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleControllerTest {

    @Mock
    private RuleCache ruleCache;

    private RuleController ruleController;

    @BeforeEach
    void setUp() {
        ruleController = new RuleController(ruleCache);
    }

    @Test
    @DisplayName("GET /api/v1/rules는 RuleCache.getAll() 결과를 그대로 200으로 반환한다")
    void getRulesReturnsCacheSnapshot() {
        List<ThresholdRule> rules = List.of(
                new ThresholdRule(1L, "device-1", "co2", Operator.GT, 1000.0, 1L)
        );
        when(ruleCache.getAll()).thenReturn(rules);

        ResponseEntity<Collection<ThresholdRule>> response = ruleController.getRules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(rules);
    }

    @Test
    @DisplayName("캐시가 비어있으면 빈 컬렉션을 200으로 반환한다")
    void getRulesReturnsEmptyCollectionWhenCacheEmpty() {
        when(ruleCache.getAll()).thenReturn(List.of());

        ResponseEntity<Collection<ThresholdRule>> response = ruleController.getRules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }
}