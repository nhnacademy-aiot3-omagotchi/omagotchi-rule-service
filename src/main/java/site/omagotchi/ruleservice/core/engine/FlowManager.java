package site.omagotchi.ruleservice.core.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.core.engine.dto.FlowSummary;
import site.omagotchi.ruleservice.core.engine.exception.DuplicateFlowException;
import site.omagotchi.ruleservice.core.engine.exception.FlowManagerException;
import site.omagotchi.ruleservice.core.engine.exception.FlowNotFoundException;
import site.omagotchi.ruleservice.core.flow.Flow;
import site.omagotchi.ruleservice.core.node.AbstractNode;
import site.omagotchi.ruleservice.core.parser.definition.ConnectionDefinition;
import site.omagotchi.ruleservice.core.parser.definition.FlowDefinition;
import site.omagotchi.ruleservice.core.parser.definition.NodeDefinition;
import site.omagotchi.ruleservice.core.registry.NodeRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class FlowManager {

    private final FlowEngine flowEngine;
    private final NodeRegistry nodeRegistry;
    private final Map<String, FlowEntry> flowEntries = new ConcurrentHashMap<>();

    // deploy가 등록과 시작 한 번에 함
    // 검증 -> 노드 생성(NodeRegistry) -> 배선 -> FlowEngine 등록/시작 (중복 id는 예외)
    // 배포하면 바로 RUNNING
    public void deploy(FlowDefinition flowDef) {

        if (Objects.isNull(flowDef)) {
            throw new IllegalArgumentException("flowDefinition이 null입니다.");
        }

        if (flowEntries.containsKey(flowDef.id())) {
            throw new DuplicateFlowException(flowDef.id());
        }

        Flow flow = this.buildFlow(flowDef);

        flowEngine.register(flow);

        // start가 실패하면 unregister로 되돌림
        // 노드를 다 만들고 배선까지 성공했는데, FlowEngine.start() 안에서 실패하면(예: 노드의 initialize()가 예외 던지는 경우)
        // 이미 register() 된 애매한 상태를 되돌리기 위해
        try {
            flowEngine.start(flow.getId());
        } catch (RuntimeException e) {
            flowEngine.unregister(flow.getId());
            throw e;
        }

        // FlowManager가 관리하는 상태로 등록
        flowEntries.put(flowDef.id(), new FlowEntry(flowDef));
        log.debug("[{}] 플로우 배포 및 등록 완료", flowDef.id());
    }

    private Flow buildFlow(FlowDefinition flowDef) {

        Flow flow = new Flow(flowDef.id());
        List<AbstractNode> createdNodes = new ArrayList<>();

        try {
            for (NodeDefinition nodeDef : flowDef.nodes()) {
                Map<String, Object> config = new HashMap<>(nodeDef.config());
                config.put("id", nodeDef.id()); // config에 "id"를 주입

                AbstractNode node = nodeRegistry.create(nodeDef.type(), config);
                createdNodes.add(node);

                // 만들어진 노드의 id와 실제로 일치하는지 검증
                // 이 검증이 없으면 flow.addNode(node)가 엉뚱한 id로 등록되고, 나중에 connect()가 NodeDefinition.id() 기준으로 찾다가 "노드를 찾을 수 없습니다" 같은 에러 발생할 수 있음
                if (!Objects.equals(node.getId(), nodeDef.id())) {
                    throw new FlowManagerException("[flow = %s] NodeFactory가 반환한 id(%s)가 정의된 id(%s)와 일치하지 않습니다."
                            .formatted(flowDef.id(), node.getId(), nodeDef.id()));
                }

                flow.addNode(node);
            }

            for (ConnectionDefinition connectionDef : flowDef.connections()) {
                flow.connect(
                        connectionDef.sourceNodeId(), connectionDef.sourcePort(),
                        connectionDef.targetNodeId(), connectionDef.targetPort()
                );
            }
        } catch (RuntimeException e) {
            // 원자성 처리 - 노드 생성 도중 실패하면 이미 생성된 노드를 shutdown하고 전체 실패 처리
            for (AbstractNode createdNode : createdNodes) {
                createdNode.shutdown();
            }
            throw e;
        }

        return flow;
    }

    public void start(String flowId) {
        this.requireEntry(flowId);
        flowEngine.start(flowId);
    }

    public void stop(String flowId) {
        this.requireEntry(flowId);
        flowEngine.stop(flowId);
    }

    public void restart(String flowId) {
        this.requireEntry(flowId);
        flowEngine.stop(flowId);
        flowEngine.start(flowId);
    }

    public void remove(String flowId) {
        this.requireEntry(flowId);

        if (flowEngine.getState(flowId) == FlowState.RUNNING) {
            flowEngine.stop(flowId);
        }

        flowEngine.unregister(flowId);
        flowEntries.remove(flowId);
        log.debug("[{}] 플로우 제거 완료", flowId);
    }

    public Set<String> list() {
        return Set.copyOf(flowEntries.keySet());
    }

    public FlowState getStatus(String flowId) {
        this.requireEntry(flowId);

        return flowEngine.getState(flowId);
    }

    /**
     * 단일 플로우의 요약 정보(구조 + 상태)를 조회
     * 운영 API의 GET /flows/{id} 응답 조립에 쓰임
     */
    public FlowSummary getSummary(String flowId) {
        this.requireEntry(flowId);

        FlowEntry flowEntry = flowEntries.get(flowId);

        List<String> nodeIds = flowEntry.flowDefinition().nodes().stream()
                .map(NodeDefinition::id)
                .toList();

        FlowState flowState = flowEngine.getState(flowId);

        return new FlowSummary(flowId, flowState, nodeIds);
    }

    /**
     * 배포된 모든 플로우의 요약 정보를 조회
     * 운영 API의 GET /flows 응답 조립에 쓰임
     *
     * @return
     */
    public List<FlowSummary> listSummaries() {
        return flowEntries.keySet().stream() // flowEntries.keySet() = flowId들
                .map(this::getSummary)
                .toList();
    }

    // FlowManager 차원의 존재 확인
    private void requireEntry(String flowId) {
        if (!flowEntries.containsKey(flowId)) {
            throw new FlowNotFoundException(flowId);
        }
    }
}