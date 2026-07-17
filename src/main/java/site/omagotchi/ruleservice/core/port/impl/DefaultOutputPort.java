package site.omagotchi.ruleservice.core.port.impl;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.core.connection.Connection;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.port.OutputPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
public class DefaultOutputPort implements OutputPort {

    private final String name;
    private final List<Connection> connections = new ArrayList<>();

    public DefaultOutputPort(String name) {

        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        this.name = name;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void connect(Connection connection) {

        if (Objects.isNull(connection)) {
            throw new IllegalArgumentException("connection이 null입니다.");
        }

        this.connections.add(connection);
        log.debug("[{}] connection 연결됨 (connectionId = {}, 현재 총 {}개)",
                name, connection.getId(), connections.size());
    }

    @Override
    public void send(Message message) {

        for(int i = 0; i < this.connections.size(); i++) {
            Connection connection = this.connections.get(i);

            try {
                connection.deliver(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("[{}] 인터럽트로 send 중단 (connection {}/{} 이후 미전달)",
                        this.name, i + 1, connections.size());
                return; // 인터럽트 발생하면 남은 connection에는 전달하지 않고 중단
            }
        }
    }
}