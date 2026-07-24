package site.omagotchi.ruleservice.core.engine.exception;

/**
 * Reconfigurable 노드가 새 config를 거부했을 때(reconfigure()가 예외 던졌을 때) 던짐
 * 400으로 매핑
 */
public class NodeConfigRejectedException extends FlowManagerException {

    public NodeConfigRejectedException(String flowId, String nodeId, String reason) {
        super(FlowErrorCode.NODE_CONFIG_REJECTED,
                "flowId = %s, nodeId = %s, reason = %s".formatted(flowId, nodeId, reason));
    }
}