package site.omagotchi.ruleservice.quality;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QualityConfig {

    @Bean
    public PhysicalRangeTable physicalRangeTable(QualityProperties qualityProperties){
        return new PhysicalRangeTable(qualityProperties.ranges());
    }
}
