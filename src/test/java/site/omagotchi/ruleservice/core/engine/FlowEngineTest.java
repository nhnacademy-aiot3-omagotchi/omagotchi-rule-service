package site.omagotchi.ruleservice.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.core.flow.Flow;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.RecordingNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class FlowEngineTest {

    @Test
    @DisplayName("start() 후 상태는 RUNNING이고, 모든 노드의 initialize()가 호출된다")
    void startSetsRunningStateAndInitializesAllNodes() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        RecordingNode nodeA = new RecordingNode("nodeA");
        RecordingNode nodeB = new RecordingNode("nodeB");
        flow.addNode(nodeA).addNode(nodeB);
        flowEngine.register(flow);
        flowEngine.start("flow-1");

        assertThat(flowEngine.getState("flow-1")).isEqualTo(FlowState.RUNNING);
        assertThat(nodeA.isInitialized()).isTrue();
        assertThat(nodeB.isInitialized()).isTrue();

        flowEngine.stop("flow-1"); // 다음 테스트에 스레드 안 남기고 정리
    }

    @Test
    @DisplayName("3단 파이프라인(A->B->C)이 소비 스레드 위에서 동작해 메시지가 끝까지 도달하고, traceId도 그대로 유지된다")
    void msgAndTraceIdFlowThroughThreeStagePipeline() throws InterruptedException {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");

        CountDownLatch arrived = new CountDownLatch(1);
        AtomicReference<Message> receivedAtC = new AtomicReference<>();
        AtomicReference<RecordingNode> nodeBRef = new AtomicReference<>(); // nodeB가 자기 자신을 참조하기 위한 우회

        RecordingNode nodeA = new RecordingNode("nodeA");
        nodeA.openOutputPort("out");

        RecordingNode nodeB = new RecordingNode("nodeB", msg ->
                nodeBRef.get().sendTo("out", msg));
        nodeBRef.set(nodeB);
        nodeB.openInputPort("in");
        nodeB.openOutputPort("out");

        RecordingNode nodeC = new RecordingNode("nodeC", msg -> {
            receivedAtC.set(msg);
            arrived.countDown();
        });
        nodeC.openInputPort("in");

        flow.addNode(nodeA).addNode(nodeB).addNode(nodeC);
        flow.connect("nodeA", "out", "nodeB", "in");
        flow.connect("nodeB", "out", "nodeC", "in");

        flowEngine.register(flow);
        flowEngine.start("flow-1");

        Message msg = Message.of(Map.of("value", 1));
        nodeA.sendTo("out", msg);

        boolean completed = arrived.await(2, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(receivedAtC.get()).isEqualTo(msg);
        assertThat(receivedAtC.get().getTraceId()).isEqualTo(msg.getTraceId());

        flowEngine.stop("flow-1");
    }

    @Test
    @DisplayName("stop() 후 상태는 STOPPED이고 모든 노드의 shutdown()이 호출된다 (stop()이 반환하면 소비 스레드는 이미 join된 상태)")
    void stopSetsStoppedStateAndShutsDownAllNodes() {
        FlowEngine flowEngine = new FlowEngine();

        Flow flow = new Flow("flow-1");

        RecordingNode nodeA = new RecordingNode("nodeA");
        nodeA.openOutputPort("out");

        RecordingNode nodeB = new RecordingNode("nodeB");
        nodeB.openInputPort("in");

        flow.addNode(nodeA).addNode(nodeB);

        flow.connect("nodeA", "out", "nodeB", "in");

        flowEngine.register(flow);

        flowEngine.start("flow-1");

        flowEngine.stop("flow-1");

        assertThat(flowEngine.getState("flow-1")).isEqualTo(FlowState.STOPPED);
        assertThat(nodeA.isShutdown()).isTrue();
        assertThat(nodeB.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("register()에 null을 전달하면 IllegalArgumentException 던진다")
    void registerWithNullFlowThrowsException() {
        FlowEngine flowEngine = new FlowEngine();

        assertThatThrownBy(() -> flowEngine.register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 등록된 flowId로 다시 register() 하면 IllegalStateException 던진다")
    void registerDuplicateFlowIdThrowsException() {
        FlowEngine flowEngine = new FlowEngine();
        flowEngine.register(new Flow("flow-1"));

        assertThatThrownBy(() -> flowEngine.register(new Flow("flow-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flow-1");
    }

    @Test
    @DisplayName("등록되지 않은 flowId로 start/stop/getState/getNode/unregister를 호출하면 IllegalArgumentException을 던진다")
    void operationsOnUnregisteredFlowThrowException() {
        FlowEngine flowEngine = new FlowEngine();

        assertThatThrownBy(() -> flowEngine.start("ghost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flowEngine.stop("ghost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flowEngine.getState("ghost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flowEngine.getNode("ghost", "nodeA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flowEngine.unregister("ghost")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getNode()는 등록된 flow에서 nodeId에 해당하는 노드를 반환한다")
    void getNodeReturnsNodeById() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        RecordingNode nodeA = new RecordingNode("nodeA");
        flow.addNode(nodeA);
        flowEngine.register(flow);

        assertThat(flowEngine.getNode("flow-1", "nodeA")).isSameAs(nodeA);
    }

    @Test
    @DisplayName("이미 RUNNING 상태에서 start()를 다시 호출해도 예외 없이 무시된다")
    void startOnAlreadyRunningFlowIsIgnored() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        flow.addNode(new RecordingNode("nodeA"));
        flowEngine.register(flow);
        flowEngine.start("flow-1");

        assertThatCode(() -> flowEngine.start("flow-1")).doesNotThrowAnyException();
        assertThat(flowEngine.getState("flow-1")).isEqualTo(FlowState.RUNNING);

        flowEngine.stop("flow-1");
    }

    @Test
    @DisplayName("STOPPED 상태에서 stop()을 호출해도 예외 없이 무시된다")
    void stopOnAlreadyStoppedFlowIsIgnored() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        flow.addNode(new RecordingNode("nodeA"));
        flowEngine.register(flow);

        assertThatCode(() -> flowEngine.stop("flow-1")).doesNotThrowAnyException();
        assertThat(flowEngine.getState("flow-1")).isEqualTo(FlowState.STOPPED);
    }

    @Test
    @DisplayName("RUNNING 상태에서 unregister()하면 IllegalStateException을 던진다")
    void unregisterWhileRunningThrowsException() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        flow.addNode(new RecordingNode("nodeA"));
        flowEngine.register(flow);
        flowEngine.start("flow-1");

        assertThatThrownBy(() -> flowEngine.unregister("flow-1"))
                .isInstanceOf(IllegalStateException.class);

        flowEngine.stop("flow-1");
    }

    @Test
    @DisplayName("STOPPED 상태에서 unregister()하면 등록이 해제되고, 이후 조회하면 예외가 발생한다")
    void unregisterWhileStoppedRemovesFlow() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        flow.addNode(new RecordingNode("nodeA"));
        flowEngine.register(flow);

        flowEngine.unregister("flow-1");

        assertThatThrownBy(() -> flowEngine.getState("flow-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stop() 시 노드 shutdown은 등록의 역순으로 호출된다")
    void stopShutsDownNodesInReverseOrder() {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");
        List<String> shutdownOrder = new CopyOnWriteArrayList<>();

        RecordingNode nodeA = new RecordingNode("nodeA") {
            @Override
            public void shutdown() {
                super.shutdown();
                shutdownOrder.add("nodeA");
            }
        };
        RecordingNode nodeB = new RecordingNode("nodeB") {
            @Override
            public void shutdown() {
                super.shutdown();
                shutdownOrder.add("nodeB");
            }
        };
        RecordingNode nodeC = new RecordingNode("nodeC") {
            @Override
            public void shutdown() {
                super.shutdown();
                shutdownOrder.add("nodeC");
            }
        };

        flow.addNode(nodeA).addNode(nodeB).addNode(nodeC);
        flowEngine.register(flow);
        flowEngine.start("flow-1");

        flowEngine.stop("flow-1");

        assertThat(shutdownOrder).containsExactly("nodeC", "nodeB", "nodeA");
    }

    @Test
    @DisplayName("한 노드에서 예외가 발생해도 소비 스레드는 죽지 않고 이후 메시지를 계속 처리한다")
    void consumeLoopSurvivesExceptionAndKeepsProcessingSubsequentMessages() throws InterruptedException {
        FlowEngine flowEngine = new FlowEngine();
        Flow flow = new Flow("flow-1");

        CountDownLatch secondArrived = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        RecordingNode nodeA = new RecordingNode("nodeA");
        nodeA.openOutputPort("out");

        RecordingNode nodeB = new RecordingNode("nodeB", msg -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                throw new RuntimeException("의도적 실패");
            }
            secondArrived.countDown();
        });
        nodeB.openInputPort("in");

        flow.addNode(nodeA).addNode(nodeB);
        flow.connect("nodeA", "out", "nodeB", "in");

        flowEngine.register(flow);
        flowEngine.start("flow-1");

        nodeA.sendTo("out", Message.of(Map.of("order", 1)));
        nodeA.sendTo("out", Message.of(Map.of("order", 2)));

        boolean completed = secondArrived.await(2, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(callCount.get()).isEqualTo(2);

        flowEngine.stop("flow-1");
    }
}