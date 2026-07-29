package site.omagotchi.ruleservice.core.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.core.parser.definition.ConnectionDefinition;
import site.omagotchi.ruleservice.core.parser.definition.FlowDefinition;
import site.omagotchi.ruleservice.core.parser.definition.NodeDefinition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
@RequiredArgsConstructor
public class FlowParser {

    private enum VisitState {
        UNVISITED, VISITING, VISITED
    }

    private final ObjectMapper objectMapper;

    public FlowDefinition parse(String json) {
        FlowDefinition flowDef = this.readJson(json);
        this.validate(flowDef);

        return flowDef;
    }

    public FlowDefinition parse(Path path) {
        try {
            String json = Files.readString(path);
            return this.parse(json);
        } catch (IOException e) {
            throw new UncheckedIOException("플로우 정의 파일을 읽을 수 없습니다: " + path, e);
        }
    }

    private FlowDefinition readJson(String json) {
        try {
            return objectMapper.readValue(json, FlowDefinition.class);
        } catch (Exception e) {
            // Jackson이 예외를 항상 감싸서 던지므로 catch 블락을 하나로.
            // 일단 Exception으로 잡고, 그 안에서 원인 체인을 직접 파고 내려가 진짜 원인이 IllegalArgumentException인지 확인
            Throwable rootCause = this.getRootCause(e);

            if (rootCause instanceof IllegalArgumentException) {
                throw new IllegalArgumentException("플로우 정의의 필수 필드가 누락되었거나 유효하지 않습니다: " + rootCause.getMessage(), rootCause);
            }
            throw new IllegalArgumentException("플로우 정의 JSON을 파싱할 수 없습니다: " + e.getMessage(), e);
        }
    }

    // 예외는 getCause()로 자기를 만든 원인 예외를 가리킬 수 있고, 그 원인도 또 자기 원인을 가리킬 수 있음 (체인)
    // 이 메서드는 끝까지 파고 내려가서 진짜 원인 찾아냄
    // cause.getCause != cause 조건은 혹시라도 예외가 자기 자신을 원인으로 가리키는 순환참조가 있을 때 무한루프 안 빠지도록 하는 방어
    private Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;

        while (Objects.nonNull(cause.getCause()) && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        return cause;
    }

    private void validate(FlowDefinition flowDef) {
        // 호출 순서 변경 금지
        this.validateUniqueNodeIds(flowDef);
        this.validateConnectionsReferenceExistingNodes(flowDef);
        this.validateNoCycle(flowDef);
    }

    private void validateUniqueNodeIds(FlowDefinition flowDef) {
        Set<String> seen = new HashSet<>();

        for (NodeDefinition nodeDef : flowDef.nodes()) {
            if (!seen.add(nodeDef.id())) {
                throw new IllegalArgumentException("[flow = %s] 중복된 노드 ID입니다: %s"
                        .formatted(flowDef.id(), nodeDef.id()));
            }
        }
    }

    private void validateConnectionsReferenceExistingNodes(FlowDefinition flowDef) {
        Set<String> nodeIds = new HashSet<>();

        for (NodeDefinition nodeDef : flowDef.nodes()) {
            nodeIds.add(nodeDef.id());
        }

        for (ConnectionDefinition connectionDef : flowDef.connections()) {
            String sourceNodeId = connectionDef.sourceNodeId();
            if (!nodeIds.contains(sourceNodeId)) {
                throw new IllegalArgumentException("[flow = %s] 연결이 존재하지 않는 소스 노드를 참조합니다: %s (from = %s)"
                        .formatted(flowDef.id(), sourceNodeId, connectionDef.from()));
            }

            String targetNodeId = connectionDef.targetNodeId();
            if (!nodeIds.contains(targetNodeId)) {
                throw new IllegalArgumentException("[flow = %s] 연결이 존재하지 않는 대상 노드를 참조합니다: %s (to = %s)"
                        .formatted(flowDef.id(), targetNodeId, connectionDef.to()));
            }
        }
    }

    private void validateNoCycle(FlowDefinition flowDef) {
        Map<String, List<String>> adjacency = this.buildAdjacency(flowDef); // 인접 리스트
        Map<String, VisitState> visitStates = new HashMap<>();

        for (NodeDefinition nodeDef : flowDef.nodes()) {
            visitStates.put(nodeDef.id(), VisitState.UNVISITED);
        }

        for (NodeDefinition nodeDef : flowDef.nodes()) {
            if (visitStates.get(nodeDef.id()) == VisitState.UNVISITED) {
                List<String> path = new ArrayList<>();
                this.detectCycle(nodeDef.id(), adjacency, visitStates, path, flowDef.id());
            }
        }
    }

    // 재귀 DFS
    private void detectCycle(String nodeId, Map<String, List<String>> adjacency,
                             Map<String, VisitState> visitStates, List<String> path, String flowId) {

        visitStates.put(nodeId, VisitState.VISITING);
        path.add(nodeId);

        for (String neighborId : adjacency.getOrDefault(nodeId, List.of())) {
            VisitState neighborState = visitStates.get(neighborId);

            if (neighborState == VisitState.VISITING) {
                path.add(neighborId);
                throw new IllegalArgumentException("[flow = %s] 순환 참조가 발견되었습니다: %s"
                        .formatted(flowId, String.join(" -> ", path)));
            }

            if (neighborState == VisitState.UNVISITED) {
                this.detectCycle(neighborId, adjacency, visitStates, path, flowId);
            }
        }

        path.remove(path.size() - 1);
        visitStates.put(nodeId, VisitState.VISITED);
    }

    private Map<String, List<String>> buildAdjacency(FlowDefinition flowDef) {
        Map<String, List<String>> adjacency = new HashMap<>();

        for (NodeDefinition nodeDef : flowDef.nodes()) {
            adjacency.put(nodeDef.id(), new ArrayList<>());
        }

        for (ConnectionDefinition connectionDef : flowDef.connections()) {
            adjacency.get(connectionDef.sourceNodeId()).add(connectionDef.targetNodeId());
        }

        return adjacency;
    }
}