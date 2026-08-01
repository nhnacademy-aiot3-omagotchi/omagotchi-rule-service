package site.omagotchi.ruleservice.writer.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influx")
public record InfluxDbProperties(
   String url,
   String token,
   String org,
   Buckets buckets,
   Batch batch
) {
    public record Buckets(
            String raw,
            String avg1m,
            String avg1h,
            String avg1d
    ){}

    public record Batch(
            int size,
            long flushIntervalMs
    ){}
}
