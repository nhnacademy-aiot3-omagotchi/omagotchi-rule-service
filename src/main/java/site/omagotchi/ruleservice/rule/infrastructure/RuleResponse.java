package site.omagotchi.ruleservice.rule.infrastructure;

import site.omagotchi.ruleservice.rule.domain.Operator;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;

import java.util.Objects;

/**
 * GET /api/v1/internal/threshold-rules 응답 바디 */
public record RuleResponse(
        Long ruleId,
        String deviceEui,
        String metric,
        String operator,
        Double threshold,
        Long ruleVersion
) {

    public ThresholdRule toRule() {
        if (Objects.isNull(ruleId) || Objects.isNull(threshold) || Objects.isNull(ruleVersion)) {
            throw new IllegalArgumentException("필수 필드 누락 ruleId= %s, threshold=%s, ruleVersion= %s"
                    .formatted(ruleId, threshold, ruleVersion));
        }

        return new ThresholdRule(
                ruleId, deviceEui, metric, Operator.from(operator), threshold, ruleVersion);
    }
}
