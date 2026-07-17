package site.omagotchi.ruleservice.core.port;

import site.omagotchi.ruleservice.core.message.Message;

public interface InputPort {
    String getName();

    void receive(Message message);
}