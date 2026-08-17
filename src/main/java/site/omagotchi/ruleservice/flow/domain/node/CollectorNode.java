package site.omagotchi.ruleservice.flow.domain.node;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Getter
public class CollectorNode extends AbstractNode {

    private final List<Message> collected = new CopyOnWriteArrayList<>();

    public CollectorNode(String id) {
        super(id);
        addInputPort("in");
    }

    @Override
    protected void onProcess(Message message) {
        collected.add(message);
        log.info("[{}] 수집: {}", getId(), message);
    }
}