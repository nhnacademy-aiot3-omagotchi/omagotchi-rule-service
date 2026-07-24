package site.omagotchi.ruleservice.core.engine.exception;

/**
 * Reconfigurable을 구현하지 않은 노드에 PATCH config를 시도했을 때 던짐
 * 409로 매핑 - '재배포 필요'
 */
public class NodeNotReconfigurableException extends FlowManagerException {

    public NodeNotReconfigurableException(String flowId, String nodeId) {
        super(FlowErrorCode.NODE_NOT_RECONFIGURABLE, "flowId = %s, nodeId = %s".formatted(flowId, nodeId));
    }
}