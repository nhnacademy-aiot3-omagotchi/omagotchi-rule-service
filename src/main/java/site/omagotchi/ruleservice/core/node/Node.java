package site.omagotchi.ruleservice.core.node;

import site.omagotchi.ruleservice.core.message.Message;

public interface Node {

    String getId();

    void initialize();

    void process(Message message);

    void shutdown();
}