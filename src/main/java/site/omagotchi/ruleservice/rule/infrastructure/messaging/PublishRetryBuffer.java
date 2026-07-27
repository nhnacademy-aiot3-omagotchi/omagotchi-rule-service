package site.omagotchi.ruleservice.rule.infrastructure.messaging;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.node.PublishMode;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RabbitMQ에 발행을 실패했을 경우 적재/재발행. <br/><br/>
 * 1. RabbitPublishNode에서 발행과정에서 Exception이 발생한 경우 <br/>
 * 2. RabbitPublishNode에서 발행은 성공했지만 RabbitMQ 브로커에서 문제가 발생한 경우*/
@Slf4j
@Component
public class PublishRetryBuffer implements SmartLifecycle {
    private static final int  DEFAULT_CAPACITY = 100_000;
    private static final long MIN_BACKOFF_MS   = 200;
    private static final long MAX_BACKOFF_MS   = 30_000;
    private static final long IDLE_SLEEP_MS    = 200;

    private final int capacity;

    private final BlockingQueue<PendingMessage> qualityQ = new LinkedBlockingQueue<>();
    private final BlockingQueue<PendingMessage> rawQ     = new LinkedBlockingQueue<>();

    private final AtomicLong droppedCount = new AtomicLong();

    private volatile boolean running = false;
    private Thread worker;

    private final RabbitTemplate rabbitTemplate;


    @Autowired
    public PublishRetryBuffer(RabbitTemplate rabbitTemplate){
        this(rabbitTemplate, DEFAULT_CAPACITY);
    }

    /** 테스트/튜닝용 - 버퍼 상한을 지정하여 생성 */
    public PublishRetryBuffer(RabbitTemplate rabbitTemplate, int capacity){
        this.rabbitTemplate = rabbitTemplate;
        this.capacity = capacity;
    }

    /**
     * 메세지 적재 메서드. <br/>
     * 전체 버퍼 크기 (Capacity)가 이미 꽉찬 경우를 고려해 오래된 메시지는 그냥 버림.<br/>
     * 단, 품질 메시지를 더 중요하게 가중치를 둬 raw부터 버림.
     * @param pm 원래 RabbitMq에 보내려고했던 메세지에 대한 정보
     */
    public synchronized void offer(PendingMessage pm) {
        if (pm == null){
            return;
        }
        while (rawQ.size() + qualityQ.size() >= capacity) {
            PendingMessage victim = rawQ.poll();
            if (victim == null){
                victim = qualityQ.poll();
            }
            if (victim != null) {
                droppedCount.incrementAndGet();
                log.warn("버퍼 포화 → 폐기(누적 {}건): key={}", droppedCount.get(), victim.routingKey());
            }
        }
        BlockingQueue<PendingMessage> q = (pm.mode() == PublishMode.QUALITY) ? qualityQ : rawQ;
        q.offer(pm);
    }

    //재발행 작업
    /** 앱 구동시 데몬 스레드를 하나 만들어 재발행 작업 시작*/
    @Override
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "publish-retry-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("PublishRetryBuffer 시작");
    }

    /**
     * 지수 백오프를 적용하여 재발행 마저 실패한다면 다시 재발행 시도 (최소 200ms 최대 30s)<br/>
     * 품질메세지가 더 가중치가높기때문에 우선순위로 poll함
     */
    private void runLoop() {
        long backoff = MIN_BACKOFF_MS;
        while (running) {
            PendingMessage pm = takeNext();
            if (pm == null) {
                sleep(IDLE_SLEEP_MS);
                continue;
            }

            if (trySend(pm)) {
                backoff = MIN_BACKOFF_MS;
            } else {
                offer(pm);
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private boolean trySend(PendingMessage pm) {
        try {
            rabbitTemplate.convertAndSend(
                    pm.exchange(), pm.routingKey(), pm.body(),
                    m -> {
                        m.getMessageProperties().setHeader("traceId", pm.traceId());
                        return m;
                        },
                    new PendingCorrelationData(pm)
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private PendingMessage takeNext() {
        PendingMessage pm = qualityQ.poll();
        return (pm != null) ? pm : rawQ.poll();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }


    /** 콜백 설정. 브로커가 nack응답을 할때 다시 버퍼에 재적재 및 재발행 시도*/
    @PostConstruct
    public void registerConfirmCallback() {
        rabbitTemplate.setConfirmCallback(
                (correlation, ack, cause) -> {
                    if (ack) {
                        return; // 브로커 정상 수신
                    }
                    if (correlation instanceof PendingCorrelationData pcd) {
                        log.warn("발행 nack → 버퍼 재적재. key={}, cause={}", pcd.pending().routingKey(), cause);
                        offer(pcd.pending());
                    } else {
                        log.error("발행 nack인데 복구 정보 없음(CorrelationData 미부착). cause={}", cause);
                    }
        });
    }


    //중단 작업
    /** 앱종료시 호출 됨 큐에 남아있는 메세지를 종료 전에 재발행 시도후 스레드 종료*/
    @Override
    public void stop() {
        running = false;
        flushRemaining();
        if (worker != null) worker.interrupt();
        log.info("PublishRetryBuffer 종료 (폐기 누적 {}건)", droppedCount.get());
    }

    private void flushRemaining() {
        PendingMessage pm;
        while ((pm = takeNext()) != null) {
            trySend(pm);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }
}