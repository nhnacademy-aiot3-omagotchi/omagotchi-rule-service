package site.omagotchi.ruleservice.flow.domain.node;

import site.omagotchi.ruleservice.flow.domain.Message;

public interface Node {

    String getId();

    void initialize();

    void process(Message message);

    void shutdown();
}