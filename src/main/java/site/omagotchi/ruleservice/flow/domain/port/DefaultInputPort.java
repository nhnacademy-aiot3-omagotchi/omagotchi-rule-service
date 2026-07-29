package site.omagotchi.ruleservice.flow.domain.port;

import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.Node;
import site.omagotchi.ruleservice.flow.domain.port.InputPort;

import java.util.Objects;

public class DefaultInputPort implements InputPort {

    private final String name;
    private final Node owner;

    public DefaultInputPort(String name, Node owner) {

        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        if (Objects.isNull(owner)) {
            throw new IllegalArgumentException("owner가 null입니다.");
        }

        this.name = name;
        this.owner = owner;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void receive(Message message) {
        this.owner.process(message);
    }
}