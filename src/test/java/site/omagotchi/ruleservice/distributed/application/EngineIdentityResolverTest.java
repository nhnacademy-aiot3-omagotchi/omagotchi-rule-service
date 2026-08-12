package site.omagotchi.ruleservice.distributed.application;

import com.netflix.appinfo.EurekaInstanceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngineIdentityResolverTest {

    @Mock
    private EurekaInstanceConfig eurekaInstanceConfig;

    @Test
    @DisplayName("기동 시점에 EngineProperties + Eureka 등록 주소로 self EngineInfo를 한 번만 계산한다")
    void buildsSelfFromEnginePropertiesAndEurekaAddress() {
        EngineProperties engineProperties = new EngineProperties("engine-a", 1);

        when(this.eurekaInstanceConfig.getIpAddress()).thenReturn("10.0.0.5");

        long before = System.currentTimeMillis();

        EngineIdentityResolver engineIdentityResolver = new EngineIdentityResolver(engineProperties, this.eurekaInstanceConfig, 8081);

        long after = System.currentTimeMillis();

        EngineInfo self = engineIdentityResolver.getSelf();

        assertThat(self.engineId()).isEqualTo("engine-a");
        assertThat(self.host()).isEqualTo("10.0.0.5");
        assertThat(self.port()).isEqualTo(8081);
        assertThat(self.priority()).isEqualTo(1);
        assertThat(self.presenceStatus()).isEqualTo(PresenceStatus.SELF);
        assertThat(self.engineRole()).isNull();
        assertThat(self.startedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("host는 직접 계산 안 하고 EurekaInstanceConfig가 등록한 주소를 그대로 신뢰한다")
    void trustsEurekaRegisteredAddressOverLocalResolution() {
        EngineProperties engineProperties = new EngineProperties("engine-b", 2);
        when(this.eurekaInstanceConfig.getIpAddress()).thenReturn("172.18.0.3");

        EngineIdentityResolver engineIdentityResolver = new EngineIdentityResolver(
                engineProperties, this.eurekaInstanceConfig, 8082
        );

        assertThat(engineIdentityResolver.getSelf().host()).isEqualTo("172.18.0.3");
    }
}
