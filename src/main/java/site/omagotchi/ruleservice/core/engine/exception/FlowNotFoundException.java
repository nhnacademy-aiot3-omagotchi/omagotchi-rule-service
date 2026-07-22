package site.omagotchi.ruleservice.core.engine.exception;

/**
 * 존재하지 않는 flowId로 조회/제어를 시도했을 때 던짐
 * 404로 매핑
 */
public class FlowNotFoundException extends FlowManagerException {

    public FlowNotFoundException(String flowId) {
        super("등록되지 않은 플로우입니다: " + flowId);
    }
}