package site.omagotchi.ruleservice.flow.application;

import lombok.Getter;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import java.util.Objects;

/**
 * FlowManager 테스트 전용 더미 Activatable 노드
 * activate/deactivate 호출 결과만 기록
 */
class FakeActivatableNode extends AbstractNode implements Activatable {

    @Getter
    private volatile boolean activated = false;
    private final RuntimeException throwOnActivate;

    public FakeActivatableNode(String id) {
        this(id, null);
    }

    public FakeActivatableNode(String id, RuntimeException throwOnActivate) {
        super(id);
        this.throwOnActivate = throwOnActivate;
    }

    @Override
    protected void onProcess(Message message) {
        // 테스트에서 미사용
    }

    @Override
    public void activate() {
        if (Objects.nonNull(this.throwOnActivate)) {
            throw this.throwOnActivate;
        }

        this.activated = true;
    }

    @Override
    public void deactivate() {
        this.activated = false;
    }
}
