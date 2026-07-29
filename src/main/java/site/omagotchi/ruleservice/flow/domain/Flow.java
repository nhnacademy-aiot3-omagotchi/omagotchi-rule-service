package site.omagotchi.ruleservice.flow.domain;

import lombok.Getter;
import site.omagotchi.ruleservice.flow.domain.connection.Connection;
import site.omagotchi.ruleservice.flow.domain.connection.LocalConnection;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.port.InputPort;
import site.omagotchi.ruleservice.flow.domain.port.OutputPort;

import java.util.*;

public class Flow {

    @Getter
    private final String id;
    private final Map<String, AbstractNode> nodes = new LinkedHashMap<>();
    private final List<Wire> wires = new ArrayList<>();

    public Flow(String id) {
        if (Objects.isNull(id) || id.isBlank()) {
            throw new IllegalArgumentException("id가 null이거나 비어있습니다.");
        }

        this.id = id;
    }

    public AbstractNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public List<AbstractNode> getNodesInOrder() {
        return List.copyOf(nodes.values());
    }

    public List<Wire> getWires() {
        return List.copyOf(wires);
    }

    public Flow addNode(AbstractNode node) {
        if (Objects.isNull(node)) {
            throw new IllegalArgumentException("node가 null입니다.");
        }

        String nodeId = node.getId();
        if (nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("[flow = %s] 이미 등록된 노드 ID입니다: %s".formatted(id, nodeId));
        }

        this.nodes.put(node.getId(), node);
        return this;
    }

    public Flow connect(String sourceId, String sourcePort, String targetId, String targetPort) {

        AbstractNode sourceNode = this.nodes.get(sourceId);
        if (Objects.isNull(sourceNode)) {
            throw new IllegalArgumentException("[flow = %s] 소스 노드를 찾을 수 없습니다: %s".formatted(id, sourceId));
        }

        AbstractNode targetNode = this.nodes.get(targetId);
        if (Objects.isNull(targetNode)) {
            throw new IllegalArgumentException("[flow = %s] 대상 노드를 찾을 수 없습니다: %s".formatted(id, targetId));
        }

        OutputPort out = sourceNode.getOutputPort(sourcePort);
        if (Objects.isNull(out)) {
            throw new IllegalArgumentException("[flow = %s] 노드 %s에 OutputPort가 없습니다: %s".formatted(id, sourceId, sourcePort));
        }

        InputPort in = targetNode.getInputPort(targetPort);
        if (Objects.isNull(in)) {
            throw new IllegalArgumentException("[flow = %s] 노드 %s에 InputPort가 없습니다: %s".formatted(id, targetId, targetPort));
        }

        Connection connection = new LocalConnection();

        Wire wire = new Wire(connection, in, sourceNode.getId(), targetNode.getId());
        out.connect(connection);

        this.wires.add(wire);

        return this;
    }
}