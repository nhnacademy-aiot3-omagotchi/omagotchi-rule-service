package site.omagotchi.ruleservice.inbound.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(
        String brokerUrl,
        String clientId,
        String username,
        String password
) {
}