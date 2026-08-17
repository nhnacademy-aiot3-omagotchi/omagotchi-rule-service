package site.omagotchi.ruleservice.distributed.application.port;

/**
 * 이 엔진 자신이 등록된 주소(host)를 조회하는 창구
 * 실제 조회 방식(Eureka)은 infrastructure 구현체가 담당
 */
public interface EngineAddressResolverPort {

    String resolveHost();
}
