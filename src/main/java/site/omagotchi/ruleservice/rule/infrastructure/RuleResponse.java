package site.omagotchi.ruleservice.rule.infrastructure;

import site.omagotchi.ruleservice.rule.domain.ThresholdRule;

/**
 * GET /api/버전/threshold-rules 응답 바디 */
public record RuleResponse(
        Long ruleId,
        String deviceEui,
        String metric,
        String operator,
        Double threshold,
        Long ruleVersion
) {
    public ThresholdRule toRule() {
        return ThresholdRule.of(ruleId, deviceEui, metric, operator, threshold, ruleVersion);
    }
}
