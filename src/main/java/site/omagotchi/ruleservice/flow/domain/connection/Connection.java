package site.omagotchi.ruleservice.flow.domain.connection;

import site.omagotchi.ruleservice.flow.domain.Message;

public interface Connection {

    String getId();

    void deliver(Message message) throws InterruptedException;

    Message poll() throws InterruptedException;

    int getBufferSize();

    void close();
}