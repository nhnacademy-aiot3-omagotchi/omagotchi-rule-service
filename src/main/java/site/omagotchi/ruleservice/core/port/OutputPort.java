package site.omagotchi.ruleservice.core.port;

import site.omagotchi.ruleservice.core.connection.Connection;
import site.omagotchi.ruleservice.core.message.Message;

public interface OutputPort {

    String getName();

    void connect(Connection connection);

    void send(Message message); // 연결된 모든 Connection에 전달 (1:N)
}