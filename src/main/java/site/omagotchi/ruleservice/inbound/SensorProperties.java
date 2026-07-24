package site.omagotchi.ruleservice.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(
        String brokerUrl,
        String clientId
) {
}
