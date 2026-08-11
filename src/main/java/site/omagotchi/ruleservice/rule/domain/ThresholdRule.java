package site.omagotchi.ruleservice.rule.domain;


import java.util.Objects;

public record ThresholdRule(
        long ruleId,
        String deviceEui,
        String metric,
        Operator operator,
        double threshold,
        long ruleVersion
) {

    public ThresholdRule {

        if (Objects.isNull(deviceEui) || deviceEui.isBlank()) {
            throw new IllegalArgumentException("deviceEui가 null이거나 비어있습니다.");
        }

        if (Objects.isNull(metric) || metric.isBlank()) {
            throw new IllegalArgumentException("metric이 null이거나 비어있습니다.");
        }

        if (Objects.isNull(operator)) {
            throw new IllegalArgumentException("operator가 null입니다.");
        }

        if (!Double.isFinite(threshold)) {
            throw new IllegalArgumentException("threshold가 유효하지 않습니다: " + threshold);
        }

    }

    /** 룰 버전을 비교하여 최신인지를 확인*/
    public boolean isNewerThan(ThresholdRule other) {
        return other == null || this.ruleVersion > other.ruleVersion;
    }


    /** 임계값과 비교연산자를 통해서 자동으로 연산*/
    public boolean ruleHit(double value) {
        return operator.matches(value, threshold);
    }


}
