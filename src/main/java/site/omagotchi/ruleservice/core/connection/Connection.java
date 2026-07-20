package site.omagotchi.ruleservice.core.connection;

import site.omagotchi.ruleservice.core.message.Message;

public interface Connection {

    String getId();

    void deliver(Message message) throws InterruptedException;

    Message poll() throws InterruptedException;

    int getBufferSize();

    void close();
}