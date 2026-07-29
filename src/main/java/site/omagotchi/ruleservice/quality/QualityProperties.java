package site.omagotchi.ruleservice.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "quality")
public record QualityProperties(
        Map<String, PhysicalRange> ranges,
        List<SensorId> inventory
) {

    public record SensorId(
            String deviceEui,
            String measurement,
            Integer expectedIntervalSeconds
    ){

    }
}
