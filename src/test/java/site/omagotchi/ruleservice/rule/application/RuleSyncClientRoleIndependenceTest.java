package site.omagotchi.ruleservice.rule.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSyncClientRoleIndependenceTest {

    @Test
    @DisplayName("RuleSyncClient는 Activatable을 구현하지 않는다 - 웜 스탠바이 요구사항(역할과 무관하게 항상 동기화)이 실수로 깨지는 것을 방지")
    void mustNotBeGatedByEngineRole() {

        assertThat(Activatable.class.isAssignableFrom(RuleSyncClient.class)).isFalse();
    }
}
