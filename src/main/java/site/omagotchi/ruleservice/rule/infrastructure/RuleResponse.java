package site.omagotchi.ruleservice.rule.infrastructure;

import site.omagotchi.ruleservice.rule.domain.Operator;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;

/**
 * core에 GET 요청을 보냈을 경우 반환되는 응답 객체
 */
public record RuleResponse(
        Long ruleId,
        String deviceEui,
        String metric,
        String operator,
        Double threshold,
        Long ruleVersion
) {
    /**
     * 응답은 null을 허용하여 느슨하게 입력받음. 검증은 ThresholdRule에서 실행
     */
    public ThresholdRule toRule() {
        return new ThresholdRule(ruleId, deviceEui, metric, Operator.from(operator), threshold, ruleVersion);
    }
}
