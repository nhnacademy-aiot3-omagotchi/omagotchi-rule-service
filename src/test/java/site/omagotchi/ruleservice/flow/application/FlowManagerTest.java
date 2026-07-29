package site.omagotchi.ruleservice.flow.application;

import site.omagotchi.ruleservice.flow.domain.FlowState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.infrastructure.parser.ConnectionDefinition;
import site.omagotchi.ruleservice.flow.infrastructure.parser.FlowDefinition;
import site.omagotchi.ruleservice.flow.infrastructure.parser.NodeDefinition;
import site.omagotchi.ruleservice.flow.domain.registry.NodeRegistry;
import site.omagotchi.ruleservice.global.exception.BusinessException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowManagerTest {

    @Mock
    private FlowEngine flowEngine;

    @Mock
    private NodeRegistry nodeRegistry;

    private FlowManager flowManager;

    @BeforeEach
    void setUp() {
        flowManager = new FlowManager(flowEngine, nodeRegistry);
    }

    private AbstractNode mockNode(String id) {
        AbstractNode node = mock(AbstractNode.class);
        lenient().when(node.getId()).thenReturn(id);
        return node;
    }

    private FlowDefinition singleNodeFlowDef(String flowId, String nodeId) {
        return new FlowDefinition(
                flowId, null, null,
                List.of(new NodeDefinition(nodeId, "SampleSource", null)),
                null
        );
    }

    @Nested
    @DisplayName("deploy")
    class Deploy {

        @Test
        @DisplayName("배포에 성공하면 flowEngine에 register/start가 호출되고 list()로 조회 가능해진다")
        void deploySuccessRegistersAndStartsFlow() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);

            flowManager.deploy(flowDef);

            verify(flowEngine).register(any());
            verify(flowEngine).start("flow-1");
            assertThat(flowManager.list()).containsExactly("flow-1");
        }

        @Test
        @DisplayName("배포 성공 후 getStatus로 상태 조회가 가능하다 (flowEntries 저장 회귀 테스트)")
        void deployedFlowIsQueryableViaGetStatus() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            when(flowEngine.getState("flow-1")).thenReturn(FlowState.RUNNING);

            flowManager.deploy(flowDef);
            FlowState status = flowManager.getStatus("flow-1");

            assertThat(status).isEqualTo(FlowState.RUNNING);
        }

        @Test
        @DisplayName("배포 성공 후 start/stop 호출 시 '등록되지 않은 플로우' 예외가 발생하지 않는다 (flowEntries 저장 회귀 테스트)")
        void deployedFlowCanBeStartedAndStoppedWithoutException() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);

            flowManager.deploy(flowDef);

            assertThatCode(() -> flowManager.start("flow-1")).doesNotThrowAnyException();
            assertThatCode(() -> flowManager.stop("flow-1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("flowDefinition이 null이면 IllegalArgumentException을 던진다")
        void nullFlowDefinitionThrowsException() {
            assertThatThrownBy(() -> flowManager.deploy(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("이미 배포된 flowId를 다시 배포하면 BusinessException을 던진다")
        void duplicateDeployThrowsException() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);

            flowManager.deploy(flowDef);

            assertThatThrownBy(() -> flowManager.deploy(flowDef))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 배포된 플로우");

            // 중복 배포 시도는 flowEngine에 두 번째 register가 일어나지 않아야 한다
            verify(flowEngine, times(1)).register(any());
        }

        @Test
        @DisplayName("NodeFactory가 반환한 노드의 id가 정의된 id와 다르면 IllegalStateException을 던지고 생성된 노드는 shutdown된다")
        void mismatchedNodeIdThrowsExceptionAndShutsDownCreatedNode() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode wrongIdNode = mockNode("WRONG_ID");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(wrongIdNode);

            assertThatThrownBy(() -> flowManager.deploy(flowDef))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("일치하지 않습니다");

            verify(wrongIdNode).shutdown();
            verify(flowEngine, never()).register(any());
            assertThat(flowManager.list()).isEmpty();
        }

        @Test
        @DisplayName("노드 생성 도중 예외가 발생하면 이미 생성된 노드들은 shutdown되고 flowEntries에 남지 않는다")
        void nodeCreationFailureShutsDownAlreadyCreatedNodes() {
            FlowDefinition flowDef = new FlowDefinition(
                    "flow-1", null, null,
                    List.of(
                            new NodeDefinition("nodeA", "SampleSource", null),
                            new NodeDefinition("nodeB", "SampleSink", null)
                    ),
                    null
            );

            AbstractNode nodeA = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(nodeA);
            when(nodeRegistry.create(eq("SampleSink"), any())).thenThrow(new RuntimeException("생성 실패"));

            assertThatThrownBy(() -> flowManager.deploy(flowDef))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("생성 실패");

            verify(nodeA).shutdown();
            assertThat(flowManager.list()).isEmpty();
        }

        @Test
        @DisplayName("연결(connect) 단계에서 예외가 발생하면 이미 생성된 노드들은 shutdown되고 flowEntries에 남지 않는다 (원자성 회귀 테스트)")
        void connectFailureShutsDownAlreadyCreatedNodes() {
            FlowDefinition flowDef = new FlowDefinition(
                    "flow-1", null, null,
                    List.of(
                            new NodeDefinition("nodeA", "SampleSource", null),
                            new NodeDefinition("nodeB", "SampleSink", null)
                    ),
                    List.of(new ConnectionDefinition("nodeA:out", "nodeB:in"))
            );

            AbstractNode nodeA = mockNode("nodeA");
            AbstractNode nodeB = mockNode("nodeB");
            // nodeA에 out 포트가 없어서 connect()가 실패하도록 유도 (getOutputPort가 null 반환)
            lenient().when(nodeA.getOutputPort("out")).thenReturn(null);
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(nodeA);
            when(nodeRegistry.create(eq("SampleSink"), any())).thenReturn(nodeB);

            assertThatThrownBy(() -> flowManager.deploy(flowDef))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OutputPort");

            verify(nodeA).shutdown();
            verify(nodeB).shutdown();
            assertThat(flowManager.list()).isEmpty();
            verify(flowEngine, never()).register(any());
        }

        @Test
        @DisplayName("flowEngine.start()가 실패하면 unregister되고 flowEntries에 남지 않는다")
        void startFailureUnregistersFlowAndDoesNotPersistEntry() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            doThrow(new RuntimeException("start 실패")).when(flowEngine).start("flow-1");

            assertThatThrownBy(() -> flowManager.deploy(flowDef))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("start 실패");

            verify(flowEngine).unregister("flow-1");
            assertThat(flowManager.list()).isEmpty();
            assertThatThrownBy(() -> flowManager.getStatus("flow-1"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("start / stop / restart")
    class StartStopRestart {

        @Test
        @DisplayName("등록되지 않은 flowId로 start()를 호출하면 BusinessException을 던진다")
        void startUnregisteredFlowThrowsException() {
            assertThatThrownBy(() -> flowManager.start("ghost"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("등록되지 않은 플로우");

            verify(flowEngine, never()).start(any());
        }

        @Test
        @DisplayName("등록되지 않은 flowId로 stop()을 호출하면 BusinessException을 던진다")
        void stopUnregisteredFlowThrowsException() {
            assertThatThrownBy(() -> flowManager.stop("ghost"))
                    .isInstanceOf(BusinessException.class);

            verify(flowEngine, never()).stop(any());
        }

        @Test
        @DisplayName("배포된 플로우를 restart하면 stop 후 start가 호출된다")
        void restartCallsStopThenStart() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            flowManager.deploy(flowDef);

            flowManager.restart("flow-1");

            InOrder inOrder = inOrder(flowEngine);
            inOrder.verify(flowEngine).stop("flow-1");
            inOrder.verify(flowEngine).start("flow-1");
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("RUNNING 상태의 플로우를 remove하면 stop 후 unregister되고 목록에서 사라진다")
        void removeRunningFlowStopsThenUnregisters() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            flowManager.deploy(flowDef);
            when(flowEngine.getState("flow-1")).thenReturn(FlowState.RUNNING);

            flowManager.remove("flow-1");

            verify(flowEngine).stop("flow-1");
            verify(flowEngine).unregister("flow-1");
            assertThat(flowManager.list()).isEmpty();
        }

        @Test
        @DisplayName("STOPPED 상태의 플로우를 remove하면 stop을 호출하지 않고 unregister만 한다")
        void removeStoppedFlowSkipsStop() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            flowManager.deploy(flowDef);
            when(flowEngine.getState("flow-1")).thenReturn(FlowState.STOPPED);

            flowManager.remove("flow-1");

            verify(flowEngine, never()).stop(any());
            verify(flowEngine).unregister("flow-1");
        }

        @Test
        @DisplayName("등록되지 않은 flowId로 remove()를 호출하면 BusinessException을 던진다")
        void removeUnregisteredFlowThrowsException() {
            assertThatThrownBy(() -> flowManager.remove("ghost"))
                    .isInstanceOf(BusinessException.class);

            verify(flowEngine, never()).unregister(any());
        }
    }

    @Nested
    @DisplayName("list / getStatus")
    class ListAndGetStatus {

        @Test
        @DisplayName("아무것도 배포되지 않았으면 list()는 빈 Set을 반환한다")
        void emptyListWhenNothingDeployed() {
            assertThat(flowManager.list()).isEmpty();
        }

        @Test
        @DisplayName("등록되지 않은 flowId로 getStatus()를 호출하면 BusinessException을 던진다")
        void getStatusForUnregisteredFlowThrowsException() {
            assertThatThrownBy(() -> flowManager.getStatus("ghost"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("getNode / getNodeConfig")
    class GetNodeAndGetNodeConfig {

        @Test
        @DisplayName("존재하는 노드를 조회하면 flowEngine이 반환한 노드 인스턴스를 그대로 돌려준다")
        void getNodeReturnsNodeFromFlowEngineTest() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);

            flowManager.deploy(flowDef);
            when(flowEngine.getNode("flow-1", "nodeA")).thenReturn(node);

            AbstractNode result = flowManager.getNode("flow-1", "nodeA");

            assertThat(result).isSameAs(node);
        }

        @Test
        @DisplayName("등록되지 않은 flowId로 getNode()를 호출하면 BusinessException을 던진다")
        void getNodeForUnregisteredFlowThrowsException() {
            assertThatThrownBy(() -> flowManager.getNode("ghost", "nodeA"))
                    .isInstanceOf(BusinessException.class);

            verify(flowEngine, never()).getNode(any(), any());
        }

        @Test
        @DisplayName("존재하는 플로우인데 없는 노드면 BusinessException을 던진다")
        void getNodeForMissingNodeThrowsBusinessException() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            flowManager.deploy(flowDef);
            when(flowEngine.getNode("flow-1", "ghost-node")).thenReturn(null);

            assertThatThrownBy(() -> flowManager.getNode("flow-1", "ghost-node"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("정적 정의의 config를 그대로 조회한다")
        void getNodeConfigReturnsStaticDefinitionConfig() {
            Map<String, Object> config = Map.of("min", 300, "max", 5000);
            FlowDefinition flowDef = new FlowDefinition(
                    "flow-1", null, null,
                    List.of(new NodeDefinition("nodeA", "SampleSource", config)),
                    null
            );
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            flowManager.deploy(flowDef);

            Map<String, Object> result = flowManager.getNodeConfig("flow-1", "nodeA");

            assertThat(result).containsExactlyInAnyOrderEntriesOf(config);
        }

        @Test
        @DisplayName("등록되지 않은 flowId로 getNodeConfig()를 호출하면 BusinessException을 던진다")
        void getNodeConfigForUnregisteredFlowThrowsException() {
            assertThatThrownBy(() -> flowManager.getNodeConfig("ghost", "nodeA"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("존재하는 플로우인데 없는 노드면 getNodeConfig()도 BusinessException을 던진다")
        void getNodeConfigForMissingNodeThrowsBusinessException() {
            FlowDefinition flowDef = singleNodeFlowDef("flow-1", "nodeA");
            AbstractNode node = mockNode("nodeA");
            when(nodeRegistry.create(eq("SampleSource"), any())).thenReturn(node);
            flowManager.deploy(flowDef);

            assertThatThrownBy(() -> flowManager.getNodeConfig("flow-1", "ghost-node"))
                    .isInstanceOf(BusinessException.class);
        }
    }
}