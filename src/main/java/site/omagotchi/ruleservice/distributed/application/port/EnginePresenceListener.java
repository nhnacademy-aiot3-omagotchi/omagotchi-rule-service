package site.omagotchi.ruleservice.distributed.application.port;

/**
 * EngineDiscoveryService(인프라)가 "누군가의 생존 상태가 바뀌었다"라는 것을 감지했을 때, application 계층에 "역할 다시 판단해봐"라고 알리는 콜백
 */
public interface EnginePresenceListener {

    void onPresenceChanged();
}