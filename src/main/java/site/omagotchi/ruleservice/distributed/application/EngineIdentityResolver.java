package site.omagotchi.ruleservice.distributed.application;

import com.netflix.appinfo.EurekaInstanceConfig;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

/**
 * 이 엔진 자신의 EngineInfo를 기동 시점에 한 번만 계산해서 보관
 * presentation(GET /self)과 향후 GET /engines의 SELF 항목이 같은 값을 공유하기 위함
 * host는 직접 계산하지 않고, Eureka가 이미 확정한 등록 주소(EurekaInstanceConfig)를 그대로 사용
 * - InetAddress.getLocalHost()는 컨테이너 환경에서 루프백 주소를 반환할 수 있어 신뢰할 수 없고,
 * - 다른 엔진이 Eureka로 나를 찾아올 때 쓰는 주소와 어긋나면 안 되기 떄문
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
                                  EurekaInstanceConfig eurekaInstanceConfig,
                                  @Value("${server.port}") int port) {

        this.self = new EngineInfo(
                engineProperties.id(), // engineId
                eurekaInstanceConfig.getIpAddress(), // host
                port,
                engineProperties.priority(), // priority
                System.currentTimeMillis(), // startedAt
                PresenceStatus.SELF, // presenceStatus
                null // engineRole - 기동 시점엔 아직 판정 전이라 항상 null, 응답 조립 시 EngineRoleService.getCurrentRole()로 덮어씀
        );
    }
}