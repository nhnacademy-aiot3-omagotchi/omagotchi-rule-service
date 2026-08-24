package site.omagotchi.ruleservice.flow.domain.node;

import lombok.Getter;
import site.omagotchi.ruleservice.flow.domain.Message;

import java.util.function.Consumer;

/**
 * 범용 테스트 노드
 * onProcess 동작을 생성 시 주입받아 자유롭게 커스텀
 * protected 멤버(addInputPort 등)를 다른 패키지의 테스트에서도 쓸 수 있도록 public 래퍼 제공
 */
public class RecordingNode extends AbstractNode {

    private final Consumer<Message> behavior;

    @Getter
    private volatile Message lastProcessed;

    @Getter
    private volatile boolean initialized = false;

    @Getter
    private volatile boolean shutdown = false;

    public RecordingNode(String id, Consumer<Message> behavior) {
        super(id);
        this.behavior = behavior;
    }

    public RecordingNode(String id) {
        this(id, message -> {
        });
    }

    @Override
    protected void onProcess(Message message) {
        this.lastProcessed = message;
        this.behavior.accept(message);
    }

    @Override
    public void initialize() {
        this.initialized = true;
    }

    @Override
    public void shutdown() {
        this.shutdown = true;
    }

    public void openInputPort(String name) {
        addInputPort(name);
    }

    public void openOutputPort(String name) {
        addOutputPort(name);
    }

    public void sendTo(String portName, Message message) {
        send(portName, message);
    }
}