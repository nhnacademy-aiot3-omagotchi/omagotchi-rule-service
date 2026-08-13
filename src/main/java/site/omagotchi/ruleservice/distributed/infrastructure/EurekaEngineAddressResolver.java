package site.omagotchi.ruleservice.distributed.infrastructure;

import com.netflix.appinfo.EurekaInstanceConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.distributed.application.port.EngineAddressResolverPort;

/**
 * host는 직접 계산하지 않고, Eureka가 이미 확정한 등록 주소(EurekaInstanceConfig)를 그대로 사용
 * - InetAddress.getLocalHost()는 컨테이너 환경에서 루프백 주소를 반환할 수 있어 신뢰할 수 없고,
 * - 다른 엔진이 Eureka로 나를 찾아올 때 쓰는 주소와 어긋나면 안 되기 떄문
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EurekaEngineAddressResolver implements EngineAddressResolverPort {

    private final EurekaInstanceConfig eurekaInstanceConfig;

    @Override
    public String resolveHost() {
        return this.eurekaInstanceConfig.getIpAddress();
    }
}
