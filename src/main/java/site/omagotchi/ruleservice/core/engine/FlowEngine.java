package site.omagotchi.ruleservice.core.engine;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.core.flow.Flow;
import site.omagotchi.ruleservice.core.flow.Wire;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class FlowEngine {

    private final Map<String, FlowExecution> executions = new ConcurrentHashMap<>();

    public void register(Flow flow) {
        if (Objects.isNull(flow)) {
            throw new IllegalArgumentException("flow가 null입니다.");
        }

        if (executions.containsKey(flow.getId())) {
            throw new IllegalStateException("이미 등록된 플로우입니다: " + flow.getId());
        }

        executions.put(flow.getId(), new FlowExecution(flow));
        log.debug("[{}] 플로우 등록", flow.getId());
    }

    // 등록 취소 메서드
    public void unregister(String flowId) {
        FlowExecution flowExecution = this.requireExecution(flowId);

        // RUNNING 상태에서 바로 지우는 것 막음
        // 워커 스레드가 아직 돌고 있는데 FlowEngine이 그 존재 자체를 까먹으면 정지시킬 방법이 없어지기 때문에.
        // "먼저 멈추고 나서 지워라" 순서
        if (flowExecution.getFlowState() == FlowState.RUNNING) {
            throw new IllegalArgumentException("[%s] RUNNING 상태에서는 등록 해제할 수 없습니다. 먼저 stop() 하세요.".formatted(flowId));
        }

        executions.remove(flowId);
        log.debug("[{}] 플로우 등록 해제", flowId);
    }

    public void start(String flowId) {
        FlowExecution flowExecution = this.requireExecution(flowId);

        if (flowExecution.getFlowState() == FlowState.RUNNING) {
            log.warn("[{}] 이미 RUNNING 상태입니다 - start() 무시", flowId);
            return;
        }

        Flow flow = flowExecution.getFlow();
        for (AbstractNode node : flow.getNodesInOrder()) {
            node.initialize();
        }

        List<Thread> workers = new ArrayList<>();
        for (Wire wire : flow.getWires()) {
            Thread worker = new Thread(
                    () -> this.consumeLoop(wire),
                    "Worker-" + wire.sourceNodeId() + "-" + wire.targetNodeId() + "-" + flowId
            );
            worker.start();
            workers.add(worker);
        }

        flowExecution.setWorkerThreads(workers);
        flowExecution.setFlowState(FlowState.RUNNING);
        log.debug("[{}] 플로우 시작 - 소비 스레드 {}개", flowId, workers.size());
    }

    private void consumeLoop(Wire wire) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Message message = wire.connection().poll();
                wire.targetPort().receive(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stop(String flowId) {
        FlowExecution flowExecution = this.requireExecution(flowId);

        if (flowExecution.getFlowState() == FlowState.STOPPED) {
            log.warn("[{}] 이미 STOPPED 상태입니다 - stop() 무시", flowId);
            return;
        }

        for (Thread worker : flowExecution.getWorkerThreads()) {
            worker.interrupt();
        }

        for (Thread worker : flowExecution.getWorkerThreads()) {
            this.joinQuietly(worker);
        }

        List<AbstractNode> nodesInOrder = flowExecution.getFlow().getNodesInOrder();
        for (int i = nodesInOrder.size() - 1; i >= 0; i--) {
            nodesInOrder.get(i).shutdown();
        }

        flowExecution.setWorkerThreads(Collections.emptyList());
        flowExecution.setFlowState(FlowState.STOPPED);
        log.debug("[{}] 플로우 정지 완료", flowId);
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private FlowExecution requireExecution(String flowId) {
        FlowExecution flowExecution = executions.get(flowId);

        if (Objects.isNull(flowExecution)) {
            throw new IllegalArgumentException("등록되지 않은 플로우입니다: " + flowId);
        }

        return flowExecution;
    }

    public FlowState getState(String flowId) {
        return this.requireExecution(flowId).getFlowState();
    }
}