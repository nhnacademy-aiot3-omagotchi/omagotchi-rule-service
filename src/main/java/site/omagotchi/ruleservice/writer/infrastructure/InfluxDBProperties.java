package site.omagotchi.ruleservice.writer.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influx")
public record InfluxDbProperties(
   String url,
   String token,
   String org,
   Buckets buckets,
   Batch batch,
   Retention retention
) {
    public record Buckets(
            String raw,
            String avg1h,
            String avg1d
    ){}

    public record Retention(
            int rawDays,
            int avg1hDays,
            int avg1dDays
    ){}

    public record Batch(
            int size,
            long flushIntervalMs
    ){}


}
