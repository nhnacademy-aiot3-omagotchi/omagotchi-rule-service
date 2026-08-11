package site.omagotchi.ruleservice.flow.application;

import lombok.Getter;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

/**
 * FlowManager 테스트 전용 더미 Activatable 노드
 * activate/deactivate 호출 결과만 기록
 */
class FakeActivatableNode extends AbstractNode implements Activatable {

    @Getter
    private volatile boolean activated = false;

    public FakeActivatableNode(String id) {
        super(id);
    }

    @Override
    protected void onProcess(Message message) {
        // 테스트에서 미사용
    }

    @Override
    public void activate() {
        this.activated = true;
    }

    @Override
    public void deactivate() {
        this.activated = false;
    }
}
