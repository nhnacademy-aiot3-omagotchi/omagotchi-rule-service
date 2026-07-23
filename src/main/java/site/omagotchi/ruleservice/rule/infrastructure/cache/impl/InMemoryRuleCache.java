package site.omagotchi.ruleservice.rule.infrastructure.cache.impl;

import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.infrastructure.cache.RuleCache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRuleCache implements RuleCache {

    private final ConcurrentHashMap<String, ThresholdRule> rules = new ConcurrentHashMap<>();

    /**
     * 룰 평가 <br/>
     * 임계값을 넘은 경우 Optional로 감싸진 Threshold를 반환. 아니라면 빈 Optional반환
     */
    @Override
    public Optional<ThresholdRule> evaluate(String deviceEui, String metric, double value) {
        ThresholdRule rule = rules.get(generateKey(deviceEui, metric));

        if (rule == null) {
            return Optional.empty();
        }

        return rule.ruleHit(value) ? Optional.of(rule) : Optional.empty();
    }

    /**
     * 룰 단일 등록. <br/>
     * isNewerThan을 통해 입력받은 룰이 최신 버전인지 비교하여 등록 <br/>
     * get, put작업을 나눠서 진행하기때문에 스레드 동기화를 위해 synchronized 처리 <br/>
     *
     */
    @Override
    public synchronized boolean apply(ThresholdRule rule) {
        if (Objects.isNull(rule)) {
            throw new IllegalArgumentException("thresholdRule이 null입니다.");
        }

        String key = generateKey(rule.deviceEui(), rule.metric());
        ThresholdRule current = rules.get(key);

        if (rule.isNewerThan(current)) {
            rules.put(key, rule);
            return true;
        }

        return false;
    }

    /**
     * 입력받은 룰 목록을 순회하며 단일 등록 진행 <br/>
     * 단일 등록 시 최신 버전이 아니라면 false를 반환하므로 count는 갱신된 룰의 갯수만큼 증가
     */
    @Override
    public synchronized int replaceAll(Collection<ThresholdRule> coreRules) {
        int count = 0;

        for (ThresholdRule rule : coreRules) {
            if (apply(rule)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Collection<ThresholdRule> getAll() {
        return List.copyOf(rules.values());
    }

    private String generateKey(String deviceEui, String metric) {
        return deviceEui + ":" + metric;
    }
}