package site.omagotchi.ruleservice.rule.domain;

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

    /**
     * 현재 캐시에 있는 모든 룰의 스냅샷을 리턴
     * GET /rules 에서 사용 - 정본 조회가 아니라, 지금 이 엔진이 어떤 임계값으로 판정중인가 를 그대로 보여주는 용도
     */
    Collection<ThresholdRule> getAll();
}