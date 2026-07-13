package site.omagotchi.ruleservice.rule.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rules")
public class RulePingController {

    private final String applicationName;

    public RulePingController(
            @Value("${spring.application.name}") String applicationName
    ) {
        this.applicationName = applicationName;
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of(
                "service", applicationName,
                "status", "UP"
        );
    }
}