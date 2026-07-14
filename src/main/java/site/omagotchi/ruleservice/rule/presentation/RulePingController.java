package site.omagotchi.ruleservice.rule.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Nginx → Gateway → Eureka → Rule Service 전체 요청 경로를 검증하기 위한 임시 Smoke Test API
 * Actuator는 개별 컨테이너 상태 확인용. 이 API는 서비스 간 라우팅을 검증
 * 향후 부작용 없이 호출 가능한 Rule Service의 실제 API로 Smoke Test를 대체 가능하면 제거 가능
 */
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