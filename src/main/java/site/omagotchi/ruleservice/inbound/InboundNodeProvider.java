package site.omagotchi.ruleservice.inbound;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.config.SensorProperties;
import site.omagotchi.ruleservice.core.registry.NodeDescriptor;
import site.omagotchi.ruleservice.core.registry.NodeProvider;

import java.util.List;

@Component
@RequiredArgsConstructor
public class InboundNodeProvider implements NodeProvider {

    private final SensorProperties sensorProperties;

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
                                sensorProperties.clientId() + "-" + id                        );
                }),
                new NodeDescriptor("Normalizer", "SensorReading 조립 노드"
                        , config -> {
                        String id = (String) config.get("id");
                        return new NormalizerNode(id);
                })
        );
    }
}
