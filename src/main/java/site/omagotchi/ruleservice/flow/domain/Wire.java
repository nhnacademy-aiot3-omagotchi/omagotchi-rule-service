package site.omagotchi.ruleservice.flow.domain;

import site.omagotchi.ruleservice.flow.domain.connection.Connection;
import site.omagotchi.ruleservice.flow.domain.port.InputPort;

public record Wire(
        Connection connection,
        InputPort targetPort,
        String sourceNodeId,
        String targetNodeId
) {
}