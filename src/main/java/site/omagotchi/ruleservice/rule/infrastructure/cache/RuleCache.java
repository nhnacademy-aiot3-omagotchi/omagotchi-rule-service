package site.omagotchi.ruleservice.rule.infrastructure.cache;

import site.omagotchi.ruleservice.rule.domain.ThresholdRule;

import java.util.Collection;
import java.util.Optional;

/**
 * 혹시라도 레디스를 쓸 경우를 생각해서 인터페이스 생성
 */
public interface RuleCache {
    Optional<ThresholdRule> evaluate(String deviceEui, String metric, double value);

    boolean apply(ThresholdRule rule);

    int replaceAll(Collection<ThresholdRule> snapshot);

}
