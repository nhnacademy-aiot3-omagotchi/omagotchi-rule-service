package site.omagotchi.ruleservice.core.flow;

import site.omagotchi.ruleservice.core.connection.Connection;
import site.omagotchi.ruleservice.core.port.InputPort;

public record Wire(
        Connection connection,
        InputPort targetPort,
        String targetNodeId
) {
}