package site.omagotchi.ruleservice.core.engine.exception;

/**
 * 존재하지 않는 (flowId, nodeId) 조합으로 노드 조회/조작을 시도했을 때 던짐
 * 404로 매핑
 */
public class NodeNotFoundException extends FlowManagerException {

    public NodeNotFoundException(String flowId, String nodeId) {
        super(FlowErrorCode.NODE_NOT_FOUND, "flowId = %s, nodeId = %s".formatted(flowId, nodeId));
    }
}