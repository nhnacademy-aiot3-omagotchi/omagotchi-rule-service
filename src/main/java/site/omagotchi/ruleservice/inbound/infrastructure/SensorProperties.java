package site.omagotchi.ruleservice.inbound.infrastructure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(
        @NotBlank(message = "sensor.broker-url은 비어 있을 수 없습니다.")
        String brokerUrl,

        @NotBlank(message = "sensor.client-id는 비어 있을 수 없습니다.")
        String clientId,

        String username,
        String password
) {
}
