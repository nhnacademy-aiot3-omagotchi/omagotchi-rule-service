package site.omagotchi.ruleservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RuleServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleServiceApplication.class, args);
    }

}
