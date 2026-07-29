package site.omagotchi.ruleservice.core.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.core.node.RecordingNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowTest {

    @Test
    @DisplayName("addNode()로 등록한 노드를 getNode()로 조회할 수 있다")
    void registersAndLooksUpNode() {
        Flow flow = new Flow("flow-1");
        RecordingNode node = new RecordingNode("nodeA");

        flow.addNode(node);

        assertThat(flow.getNode("nodeA")).isEqualTo(node);
    }

    @Test
    @DisplayName("connect() 후 배선(Wire) 정보가 정확히 기록된다")
    void connectsNodesAndRecordsWire() {
        Flow flow = new Flow("flow-1");

        RecordingNode nodeA = new RecordingNode("nodeA");
        nodeA.openOutputPort("out");

        RecordingNode nodeB = new RecordingNode("nodeB");
        nodeB.openInputPort("in");

        flow.addNode(nodeA).addNode(nodeB);

        flow.connect("nodeA", "out", "nodeB", "in");

        assertThat(flow.getWires()).hasSize(1);
        assertThat(flow.getWires().getFirst().sourceNodeId()).isEqualTo("nodeA");
        assertThat(flow.getWires().getFirst().targetNodeId()).isEqualTo("nodeB");
    }

    @Test
    @DisplayName("존재하지 않는 소스 노드로 connect 하면 원인이 담긴 예외를 던진다")
    void connectWithUnknownSourceNodeThrowsExceptionWithReason() {
        Flow flow = new Flow("flow-1");
        RecordingNode nodeB = new RecordingNode("nodeB");
        nodeB.openInputPort("in");
        flow.addNode(nodeB);

        assertThatThrownBy(() -> flow.connect("ghost", "out", "nodeB", "in"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("소스 노드를 찾을 수 없습니다")
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("존재하지 않는 포트로 connect하면 원인이 담긴 예외를 던진다")
    void connectWithUnknownPortThrowsExceptionWithReason() {
        Flow flow = new Flow("flow-1");

        RecordingNode nodeA = new RecordingNode("nodeA");
        nodeA.openOutputPort("out");

        RecordingNode nodeB = new RecordingNode("nodeB");
        // nodeB InputPort "in" 미등록

        flow.addNode(nodeA).addNode(nodeB);

        assertThatThrownBy(() -> flow.connect("nodeA", "out", "nodeB", "in"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeB에 InputPort가 없습니다: in");
    }

    @Test
    @DisplayName("id가 null이거나 비어있으면 생성 시 IllegalArgumentException을 던진다")
    void constructorWithBlankIdThrowsException() {
        assertThatThrownBy(() -> new Flow(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Flow("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("addNode()에 null을 전달하면 IllegalArgumentException을 던진다")
    void addNodeWithNullThrowsException() {
        Flow flow = new Flow("flow-1");

        assertThatThrownBy(() -> flow.addNode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 등록된 노드 ID로 addNode()하면 IllegalArgumentException을 던진다")
    void addNodeWithDuplicateIdThrowsException() {
        Flow flow = new Flow("flow-1");
        flow.addNode(new RecordingNode("nodeA"));

        assertThatThrownBy(() -> flow.addNode(new RecordingNode("nodeA")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeA");
    }
}