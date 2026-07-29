package site.omagotchi.ruleservice.quality.infrastructure;

import site.omagotchi.ruleservice.quality.domain.PhysicalRangeTable;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QualityConfig {

    @Bean
    public PhysicalRangeTable physicalRangeTable(QualityProperties qualityProperties){
        return new PhysicalRangeTable(qualityProperties.ranges());
    }
}
