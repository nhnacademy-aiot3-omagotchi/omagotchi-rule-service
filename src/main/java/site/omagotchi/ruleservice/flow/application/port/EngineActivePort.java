package site.omagotchi.ruleservice.flow.application.port;

/**
 * 지금 이 엔진이 ACTIVE인지 조회 -> FlowManager가 재기동 후 활성화 여부를 결정할 때 사용
 * 실제 판정 로직은 distributed/EngineRoleService가 갖고 있음
 * 단일 엔진 모드(이중화 없음)에서는 항상 true를 반환하는 구현체가 대신 등록됨
 */
public interface EngineActivePort {

    boolean isSelfActive();
}
