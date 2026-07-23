package site.omagotchi.ruleservice.core.node;

import java.util.Map;

/**
 * 실행 중인 노드의 설정값을 무중단으로 변경할 수 있는 노드가 구현하는 인터페이스
 * 재기동 없이 다음 메시지부터 새 설정이 적용됨 (예: Range/Dedup/Stuck의 임계값류)
 * <p>
 * 구현 시 반드시 지켜야 할 계약: reconfigure()는 원자적이어야 함
 * 즉, 새 config 전체를 먼저 검증(로컬 변수 등에)한 뒤 전부 유효할 때만 필드에 반영해야 하며,
 * 예외를 던지는 시점에는 기존 상태가 하나도 바뀌지 않은 상태여야 함
 * 이 계약이 지켜지면 FlowConfigService의 실패 시 원복 로직은 정상적으로는 호출될 일이 없음
 */
public interface Reconfigurable {

    void reconfigure(Map<String, Object> config);
}