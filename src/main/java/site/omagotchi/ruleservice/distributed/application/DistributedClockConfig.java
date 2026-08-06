package site.omagotchi.ruleservice.distributed.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class DistributedClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
