package site.omagotchi.ruleservice.inbound;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.core.registry.NodeDescriptor;
import site.omagotchi.ruleservice.core.registry.NodeProvider;
import site.omagotchi.ruleservice.quality.LastSeenRegistry;

import java.util.List;

@Component
@RequiredArgsConstructor
public class InboundNodeProvider implements NodeProvider {

    private final SensorProperties sensorProperties;
    private final LastSeenRegistry lastSeenRegistry;
    private final MeterRegistry meterRegistry; // 수신 건수 받기 위해 주입

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                new NodeDescriptor("MqttSubscriber", "Mqtt 구독 노드"
                        , config -> {
                    String id = (String) config.get("id");
                    String topicFilter = (String) config.get("topicFilter");

                    return new MqttSubscriberNode(
                            id,
                            sensorProperties.brokerUrl(),
                            topicFilter,
                            sensorProperties.clientId() + "-" + id,
                            meterRegistry
                    );
                }),
                new NodeDescriptor("Normalizer", "SensorReading 조립 노드"
                        , config -> {
                    String id = (String) config.get("id");
                    return new NormalizerNode(id, lastSeenRegistry);
                })
        );
    }
}
