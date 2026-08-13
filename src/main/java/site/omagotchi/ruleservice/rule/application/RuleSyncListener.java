package site.omagotchi.ruleservice.rule.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.infrastructure.InMemoryRuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.RuleResponse;
@Slf4j
@Component
public class RuleSyncListener {
    public static final String EXCHANGE_RULE_CHANGED = "omagotchi.rule.changed.exchange";

    private final InMemoryRuleCache inMemoryRuleCache;
    private final Counter rejectCounter;

    public RuleSyncListener(InMemoryRuleCache inMemoryRuleCache, MeterRegistry registry){
        this.inMemoryRuleCache = inMemoryRuleCache;
        this.rejectCounter = registry.counter("rule.sync.rejected");
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,
            exchange = @Exchange(value = EXCHANGE_RULE_CHANGED, type = "fanout")
    ))
    public void onRuleUpdated(RuleResponse ruleResponse){
        ThresholdRule rule;
        try{
            rule = ruleResponse.toRule();
        }catch (IllegalArgumentException e){
            rejectCounter.increment();
            log.error("이상 룰 수신. 해당 룰은 거절됩니다. {}", ruleResponse, e);
            return;
        }

        boolean applied = inMemoryRuleCache.apply(rule);

        if (applied) {
            log.info("rule.updated 반영: {}:{} v{}",
                    rule.deviceEui(), rule.metric(), rule.ruleVersion());
        } else {
            log.debug("rule.updated 무시(구버전/중복): {}:{} v{}",
                    rule.deviceEui(), rule.metric(), rule.ruleVersion());
        }
    }
}
