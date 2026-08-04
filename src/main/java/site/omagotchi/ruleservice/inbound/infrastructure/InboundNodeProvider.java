package site.omagotchi.ruleservice.inbound.infrastructure;

import site.omagotchi.ruleservice.inbound.infrastructure.MqttSubscriberNode;
import site.omagotchi.ruleservice.inbound.domain.NormalizerNode;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;
import site.omagotchi.ruleservice.quality.domain.LastSeenRegistry;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class InboundNodeProvider implements NodeProvider {
    private static final String TYPE_MQTT_SUBSCRIBER = "MqttSubscriber";
    private static final String TYPE_NORMALIZER = "Normalizer";

    private final SensorProperties sensorProperties;
    private final LastSeenRegistry lastSeenRegistry;
    private final MeterRegistry meterRegistry; // 수신 건수 받기 위해 주입

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                new NodeDescriptor(TYPE_MQTT_SUBSCRIBER, "Mqtt 구독 노드"
                        , config -> {
                    String id = Objects.requireNonNull((String) config.get("id"), "노드 config에 id가 없습니다");
                    String topicFilter = Objects.requireNonNull((String) config.get("topicFilter"), "노드 config에 topicFilter가 없습니다");

                    return new MqttSubscriberNode(
                            id,
                            sensorProperties.brokerUrl(),
                            sensorProperties.clientId() + "-" + id,
                            sensorProperties.username(),
                            sensorProperties.password(),
                            topicFilter,
                            meterRegistry
                    );
                }),
                new NodeDescriptor(TYPE_NORMALIZER, "SensorReading 조립 노드"
                        , config -> {
                    String id = (String) config.get("id");
                    return new NormalizerNode(id, lastSeenRegistry);
                })
        );
    }
}
