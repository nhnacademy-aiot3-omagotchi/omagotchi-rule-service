package site.omagotchi.ruleservice.flow.application;

import lombok.Getter;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Reconfigurable;

import java.util.Map;

/**
 * FlowConfigService 테스트 전용 더미 Reconfigurable 노드
 * Reconfigurable 계약(검증 후 일괄 반영)을 지키는 최소 구현
 */
class FakeReconfigurableNode extends AbstractNode implements Reconfigurable {

    @Getter
    private volatile int threshold;

    FakeReconfigurableNode(String id, int initialThreshold) {
        super(id);
        this.threshold = initialThreshold;
    }

    @Override
    protected void onProcess(Message message) {
        // 테스트에서 사용하지 않음
    }

    @Override
    public void reconfigure(Map<String, Object> config) {
        Object rawThreshold = config.get("threshold");

        if (!(rawThreshold instanceof Integer newThreshold) || newThreshold < 0) {
            throw new IllegalArgumentException("threshold는 0 이상의 정수여야 합니다: " + rawThreshold);
        }

        // 검증 통과했을 때만 필드에 반영 (원자성 계약)
        this.threshold = newThreshold;
    }
}