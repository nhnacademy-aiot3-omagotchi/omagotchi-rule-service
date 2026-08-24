package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.flow.domain.connection.Connection;
import site.omagotchi.ruleservice.flow.domain.Message;

import java.util.ArrayList;
import java.util.List;

public class RecordingConnection implements Connection {

    private final List<Message> received = new ArrayList<>();

    public List<Message> messages() {
        return received;
    }

    @Override
    public String getId() {
        return "";
    }

    @Override
    public void deliver(Message message) throws InterruptedException {
        received.add(message);
    }

    @Override
    public Message poll() throws InterruptedException {
        return null;
    }

    @Override
    public int getBufferSize() {
        return received.size();
    }

    @Override
    public void close() {

    }
}
