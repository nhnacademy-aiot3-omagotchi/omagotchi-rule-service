package site.omagotchi.ruleservice.core.engine.exception;

/**
 * 이미 배포된 flowId로 재배포를 시도했을 때 던짐
 * 409로 매핑됨
 */
public class DuplicateFlowException extends FlowManagerException {

    public DuplicateFlowException(String flowId) {
        super("이미 배포된 플로우입니다: " + flowId);
    }
}