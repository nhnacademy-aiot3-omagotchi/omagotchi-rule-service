package site.omagotchi.ruleservice.rule.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.domain.RuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.InMemoryRuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.RuleResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Learning 룰의 초기 적재와 주기적 동기화. */
@Slf4j
@Component
public class RuleSyncClient {

    private final RestClient learningRestClient;
    private final RuleCache ruleCache;
    private final AtomicReference<SyncState> state = new AtomicReference<>(SyncState.COLD);
    private final Counter missedPushCounter;

    public RuleSyncClient(
            RestClient learningRestClient,
            InMemoryRuleCache inMemoryRuleCache,
            MeterRegistry meterRegistry
    ) {
        this.learningRestClient = learningRestClient;
        this.ruleCache = inMemoryRuleCache;
        this.missedPushCounter = meterRegistry.counter("rule.sync.missed");
    }

    /** 애플리케이션 기동 후 초기 동기화 스레드 생성. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartUp() {
        Thread worker = new Thread(
                this::initialSyncWithRetry,
                "rule-initial-sync"
        );
        worker.setDaemon(true);
        worker.start();
    }

    /** 초기 룰 적재 실패 시 5·10·20·40·60초 간격 재시도. */
    private void initialSyncWithRetry() {
        long backOff = 5;
        while (state.get() == SyncState.COLD) {
            try (RequestIdContext.Scope ignored = RequestIdContext.openNew()) {
                try {
                    List<ThresholdRule> rules = fetchAll();
                    ruleCache.replaceAll(rules);
                    state.set(SyncState.READY);

                    if (rules.isEmpty()) {
                        log.info("설정 된 룰 없이 가동");
                    } else {
                        log.info("룰 초기 적재 완료: {}건", rules.size());
                    }
                } catch (Exception e) {
                    log.warn("Learning 연결 실패. 룰 미적용 - {}초 후 재시도", backOff, e);
                }
            }

            if (state.get() == SyncState.COLD) {
                waitBeforeRetry(backOff);
                backOff = Math.min(backOff * 2, 60);
            }
        }
    }

    /** 누락된 Push 보정을 위한 5분 주기 동기화. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void reSync() {
        if (state.get() == SyncState.COLD) {
            return;
        }

        try (RequestIdContext.Scope ignored = RequestIdContext.openNew()) {
            try {
                List<ThresholdRule> rules = fetchAll();
                int missed = ruleCache.replaceAll(rules);

                if (missed > 0) {
                    missedPushCounter.increment(missed);
                    log.warn("재동기화 보정: {}건", missed);
                }
            } catch (Exception e) {
                log.warn("재동기화 실패. 기존 캐시 유지", e);
            }
        }
    }

    private List<ThresholdRule> fetchAll() {
        List<RuleResponse> responses = learningRestClient.get()
                .uri("/api/v1/internal/threshold-rules")
                .retrieve()
                .body(new ParameterizedTypeReference<List<RuleResponse>>() {
                });

        if (Objects.isNull(responses)) {
            throw new IllegalStateException("룰 본문이 null입니다.");
        }

        List<ThresholdRule> rules = new ArrayList<>();
        for (RuleResponse response : responses) {
            try {
                rules.add(response.toRule());
            } catch (IllegalArgumentException e) {
                log.error("이상 룰 무시 - {}", response);
            }
        }

        return rules;
    }

    private void waitBeforeRetry(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
