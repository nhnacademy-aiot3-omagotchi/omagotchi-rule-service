package site.omagotchi.ruleservice.flow.application.port;

import java.util.Map;

/**
 * 포트 인터페이스
 * PATCH/start/stop/restart를 파트너 엔진에게도 전달하기 위한 포트
 * 어느 엔진이 요청을 받든, 받은 엔진이 로컬 적용 후 이 포트로 파트너에게도 같은 동작을 전달
 * (Gateway가 두 엔진 중 어디로든 라우팅할 수 있다는 전제 - 발산, failover 유실 방지)
 * 실제 파트너 주소 조회, REST 전달 로직은 distributed 내에서 구현
 * 단일 엔진 모드에서는 전달할 파트너가 없으므로 아무것도 안 하는 구현체가 대신 등록됨
 */
public interface PeerFlowSyncPort {

    void syncStart(String flowId);

    void syncStop(String flowId);

    void syncRestart(String flowId);

    void syncReconfigure(String flowId, String nodeId, Map<String, Object> config);
}
