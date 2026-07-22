package site.omagotchi.ruleservice.node.builtin;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
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

    public List<Message> getCollected(){
        return collected;
    }
}
