package site.omagotchi.ruleservice.flow.domain.port;

import site.omagotchi.ruleservice.flow.domain.connection.Connection;
import site.omagotchi.ruleservice.flow.domain.Message;

public interface OutputPort {

    String getName();

    void connect(Connection connection);

    void send(Message message); // 연결된 모든 Connection에 전달 (1:N)
}