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
import site.omagotchi.ruleservice.rule.infrastructure.cache.RuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.cache.impl.InMemoryRuleCache;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.infrastructure.dto.RuleResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 캐시 동기화 클라이언트 <br/>
 * 룰 엔진 가동 시 룰 적재 및 주기적인 확인으로 룰 동기화
 */
@Slf4j
@Component
public class RuleSyncClient {
    private final RestClient restClient;
    private final RuleCache cache;
    private final AtomicReference<SyncState> state = new AtomicReference<>(SyncState.COLD);
    private final Counter missedPushCounter;


    public RuleSyncClient(RestClient restClient, InMemoryRuleCache inMemoryRuleCache, MeterRegistry registry) {
        this.restClient = restClient;
        this.cache = inMemoryRuleCache;
        this.missedPushCounter = registry.counter("rule.sync.missed");
    }

    /**
     * 룰엔진 가동 시 자동 실행 데몬 스레드(앱이 종료될때 같이 종료되는 보조 스레드)를 하나 생성해서 작업을 맡김
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartUp() {
        Thread worker = new Thread(
                () -> initialSyncWithRetry(),
                "rule-initial-sync"
        );
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 데몬 스레드가 처리할 작업. SyncState에 따라 캐싱작업 시작 <br/>
     * 1. core에서 센서 룰 목록을 가져오고 캐시 적재(최신 룰만 갱신됨) <br/>
     * 2. 만약 요청이 실패한다면 5-10-20-40-60(max)초 간격으로 계속 시도
     */
    private void initialSyncWithRetry() {
        long backOff = 5;
        while (state.get() == SyncState.COLD) {
            try {
                List<ThresholdRule> rules = fetchAll();
                cache.replaceAll(rules);
                state.set(SyncState.READY);

                if (rules.isEmpty()) {
                    log.info("설정 된 룰 없이 가동");
                } else {
                    log.info("룰 초기 적재 완료: {}건", rules.size());
                }

            } catch (Exception e) {
                log.warn("Core 연결 실패. 룰 미적용 - {}초 후 재시도", backOff);
                sleep(backOff);
                backOff = Math.min(backOff * 2, 60);
            }
        }
    }

    /**
     * 룰 재동기화 스케줄러 <br/>
     * 5분 단위로 계속 core에서 센서 룰을 가져와서 현재 캐시에 적재. <br/>
     * rabbitMQ 에서 문제가 생겨 변경 룰을 불러오지못하는 등의 상황에 대비.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void reSync() {
        if (state.get() == SyncState.COLD) {
            return;
        }

        try {
            List<ThresholdRule> rules = fetchAll();
            int missed = cache.replaceAll(rules);

            if (missed > 0) {
                missedPushCounter.increment(missed);
                log.warn("재동기화 보정: {}건", missed);
            }
        } catch (Exception e) {
            log.warn("재동기화 실패. 기존 캐시 유지");
        }
    }

    /**
     * restClient를 통해서 core에서 센서 룰을 가져옴
     */
    private List<ThresholdRule> fetchAll() {
        List<RuleResponse> responses = restClient.get()
                .uri("/api/rules")
                .retrieve()
                .body(new ParameterizedTypeReference<List<RuleResponse>>() {
                });

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

    private void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
