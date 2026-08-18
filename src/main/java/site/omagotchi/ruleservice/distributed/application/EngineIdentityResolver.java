package site.omagotchi.ruleservice.distributed.application;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.distributed.application.port.EngineAddressResolverPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import java.time.Clock;

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
@Slf4j
public class EngineIdentityResolver {

    @Getter
    private final EngineInfo self;

    public EngineIdentityResolver(EngineProperties engineProperties,
                                  EngineAddressResolverPort engineAddressResolverPort,
                                  @Value("${server.port}") int port,
                                  Clock clock) {

        this.self = new EngineInfo(
                engineProperties.id(), // engineId
                engineAddressResolverPort.resolveHost(), // host
                port,
                engineProperties.priority(), // priority
                clock.millis(), // startedAt
                PresenceStatus.SELF, // presenceStatus
                null // engineRole - 기동 시점엔 아직 판정 전이라 항상 null, 응답 조립 시 EngineRoleService.getCurrentRole()로 덮어씀
        );

        log.info("[EngineIdentityResolver] 자기 자신 식별 완료 - id = {}, host = {}, port = {}, priority = {}, expectedPeerCount = {}",
                this.self.engineId(), this.self.host(), this.self.port(), this.self.priority(), engineProperties.expectedPeerCount());
    }
}
