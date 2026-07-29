package site.omagotchi.ruleservice.flow.domain.port;

import site.omagotchi.ruleservice.flow.domain.Message;

public interface InputPort {
    String getName();

    void receive(Message message);
}