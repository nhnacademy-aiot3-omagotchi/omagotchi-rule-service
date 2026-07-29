package site.omagotchi.ruleservice.rule.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.filter.RequestIdGenerator;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.domain.RuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.InMemoryRuleCache;
import site.omagotchi.ruleservice.rule.infrastructure.dto.RuleResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 캐시 동기화 클라이언트 <br/>
 * 룰 엔진 가동 시 룰 적재 및 주기적인 확인으로 룰 동기화
 * <p>
 * TODO 참고용 트레이드오프:
 *  재시도 한 번 한 번마다 새 request id를 발급하는 현재 방식은, '재시도 3번을 하나의 논리적 동기화 시도'로 보고 request id 하나로 묶어서 추적하고 싶다면 다른 설계가 필요할 수 있음
 *  계약 맺은 '호출하는 서비스가 새 request id 생성' 을 그대로 따르면 'HTTP 호출 하나당 하나'가 더 정확한 것으로 판단하여 현재 방식으로 구현하였음.
 */
@Slf4j
@Component
public class RuleSyncClient {

    private static final String MDC_REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final RestClient restClient;
    private final RuleCache ruleCache;
    private final AtomicReference<SyncState> state = new AtomicReference<>(SyncState.COLD);
    private final Counter missedPushCounter;

    public RuleSyncClient(RestClient restClient, InMemoryRuleCache inMemoryRuleCache, MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.ruleCache = inMemoryRuleCache;
        this.missedPushCounter = meterRegistry.counter("rule.sync.missed");
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
            // 스케줄러성 호출 - 기존 요청 컨텍스트가 없으므로 이 시도 하나를 위한 request id를 직접 발급
            MDC.put(MDC_REQUEST_ID_KEY, RequestIdGenerator.generate());

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
                log.warn("Core 연결 실패. 룰 미적용 - {}초 후 재시도", backOff, e);
                sleep(backOff);
                backOff = Math.min(backOff * 2, 60);
            } finally {
                MDC.remove(MDC_REQUEST_ID_KEY);
            }
        }
    }

    /**
     * 룰 재동기화 스케줄러 <br/>
     * 5분 단위로 계속 core에서 센서 룰을 가져와서 현재 캐시에 적재. <br/>
     * RabbitMQ에서 문제가 생겨 변경 룰을 불러오지못하는 등의 상황에 대비.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void reSync() {
        if (state.get() == SyncState.COLD) {
            return;
        }

        MDC.put(MDC_REQUEST_ID_KEY, RequestIdGenerator.generate());

        try {
            List<ThresholdRule> rules = fetchAll();
            int missed = ruleCache.replaceAll(rules);

            if (missed > 0) {
                missedPushCounter.increment(missed);
                log.warn("재동기화 보정: {}건", missed);
            }
        } catch (Exception e) {
            log.warn("재동기화 실패. 기존 캐시 유지", e);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }

    /**
     * restClient를 통해서 core에서 센서 룰을 가져옴
     */
    private List<ThresholdRule> fetchAll() {

        // 지금은 fetchAll()을 호출하는 곳이 initialSyncWithRetry()와 reSync() 둘 뿐이고, 둘 다 호출 전에 MDC.put()을 해두니까 문제 없지만,
        // 나중에 fetchAll()의 호출부가 새로 생겼을 때 그곳에서 MDC 세팅을 깜빡하고 안 하면, MDC.get(...)가 null 리턴하고, X-Request-ID에 null이 들어가버릴 수 있음
        String requestId = MDC.get(MDC_REQUEST_ID_KEY);

        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri("/api/rules");

        // requestId가 null이면 헤더를 아예 안 붙이도록 방어
        if (Objects.nonNull(requestId)) {
            spec = spec.header(REQUEST_ID_HEADER, requestId);
        }

        List<RuleResponse> responses = spec
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