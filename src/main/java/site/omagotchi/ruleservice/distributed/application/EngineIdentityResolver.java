package site.omagotchi.ruleservice.distributed.application;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.distributed.application.port.EngineAddressResolverPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

/**
 * 이 엔진 자신의 EngineInfo를 기동 시점에 한 번만 계산해서 보관
 * presentation(GET /self)과 향후 GET /engines의 SELF 항목이 같은 값을 공유하기 위함
 * host 조회는 EngineAddressResolverPort에 위임 - 구체 기술(Eureka 등)은 infrastructure가 담당
 */
@Component
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineIdentityResolver {

    @Getter
    private final EngineInfo self;

    public EngineIdentityResolver(EngineProperties engineProperties,
                                  EngineAddressResolverPort engineAddressResolverPort,
                                  @Value("${server.port}") int port) {

        this.self = new EngineInfo(
                engineProperties.id(), // engineId
                engineAddressResolverPort.resolveHost(), // host
                port,
                engineProperties.priority(), // priority
                System.currentTimeMillis(), // startedAt
                PresenceStatus.SELF, // presenceStatus
                null // engineRole - 기동 시점엔 아직 판정 전이라 항상 null, 응답 조립 시 EngineRoleService.getCurrentRole()로 덮어씀
        );
    }
}
