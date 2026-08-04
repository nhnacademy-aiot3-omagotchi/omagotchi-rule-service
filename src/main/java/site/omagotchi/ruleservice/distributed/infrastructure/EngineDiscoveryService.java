package site.omagotchi.ruleservice.distributed.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import java.util.List;
import java.util.Map;

/**
 * Eureka에 등록된 rule-service 인스턴스 중 자기 자신을 제외한 피어 목록을 조회
 * 지금은 REST 폴링(생존 판정)이 아직 없어서, Eureka 등록 여부만으로 잠정 ONLINE 처리함
 * 실제 생존 판정은 폴링 스케줄러가 추가되면 이 값을 덮어씀
 */
@Component
public class EngineDiscoveryService implements EngineDirectoryPort {

    private final DiscoveryClient discoveryClient;
    private final String applicationName;
    private final String selfEngineId;

    public EngineDiscoveryService(DiscoveryClient discoveryClient,
                                  @Value("${spring.application.name}") String applicationName,
                                  @Value("${engine.id}") String selfEngineId) {

        this.discoveryClient = discoveryClient;
        this.applicationName = applicationName;
        this.selfEngineId = selfEngineId;
    }

    /**
     * applicationName("rule-service")으로 Eureka에서 인스턴스 목록을 가져와서,
     * 각 인스턴스의 metadata-map(engine-id, engine-priority)을 읽어 EngineInfo로 변환하고,
     * engine.id가 자기 자신과 같은 건 걸러냄
     */
    @Override
    public List<EngineInfo> listEngines() {
        return this.discoveryClient.getInstances(applicationName).stream()
                .map(this::toEngineInfo)
                .filter(engineInfo -> !engineInfo.engineId().equals(this.selfEngineId))
                .toList();
    }

    private EngineInfo toEngineInfo(ServiceInstance serviceInstance) {
        Map<String, String> metadata = serviceInstance.getMetadata();

        return new EngineInfo(
                metadata.get("engine-id"),
                serviceInstance.getHost(),
                serviceInstance.getPort(),
                Integer.parseInt(metadata.get("engine-priority")),
                0L, // Eureka metadata엔 시작 시각(startedAt)이 없음 - 폴링 응답에서 채워질 예정
                PresenceStatus.ONLINE // 잠정값 - 폴링 스케줄러가 실제 판정으로 교체 예정
        );
    }
}
