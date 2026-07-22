package site.omagotchi.ruleservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(
        String brokerUrl,
        String clientId
) {
}
