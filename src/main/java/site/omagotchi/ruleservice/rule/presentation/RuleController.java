package site.omagotchi.ruleservice.rule.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.infrastructure.cache.RuleCache;

import java.util.Collection;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleCache ruleCache;

    /**
     * GET /rules
     * 이 엔진의 룰 캐시 스냅샷 조회 - 이 엔진이 지금 어떤 임계값으로 판정 중인가
     */
    @GetMapping
    public ResponseEntity<Collection<ThresholdRule>> getRules() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ruleCache.getAll());
    }
}