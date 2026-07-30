package site.omagotchi.ruleservice.flow.domain.node;

/**
 * ACTIVE/STANDBY 게이팅 대상 노드가 구현하는 계약
 * 스스로 데이터를 만들거나 끌어오는 노드만 대상 - MqttSubscriberNode(구독 시작/중단), MissingDetectorNode(타이머)
 * 반응형 노드는 입력이 없으면 자연히 유휴 상태이므로 대상 아님
 * 룰 동기화는 standby도 항상 수행
 */
public interface Activatable{

    void activate();

    void deactivate();
}