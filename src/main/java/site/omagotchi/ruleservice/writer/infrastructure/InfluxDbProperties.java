package site.omagotchi.ruleservice.writer.infrastructure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "influx")
public record InfluxDbProperties(
        @NotBlank(message = "influx.url은 비어 있을 수 없습니다.")
        String url,

        @NotBlank(message = "influx.token은 비어 있을 수 없습니다.")
        String token,

        @NotBlank(message = "influx.org는 비어 있을 수 없습니다.")
        String org,

        Buckets buckets,

        Retention retention
) {
    public record Buckets(
            String raw,

            String avg1h,

            String avg1d
    ) {
    }

    public record Retention(
            int rawDays,

            int avg1hDays,

            int avg1dDays
    ) {
    }

}
