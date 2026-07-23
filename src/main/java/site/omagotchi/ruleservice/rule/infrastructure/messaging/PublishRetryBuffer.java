package site.omagotchi.ruleservice.rule.infrastructure.messaging;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.rule.infrastructure.messaging.node.PublishMode;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
@Component
public class PublishRetryBuffer implements SmartLifecycle {
    private static final int  CAPACITY       = 100_000;
    private static final long MIN_BACKOFF_MS = 200;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final long IDLE_SLEEP_MS  = 200;

    private final BlockingQueue<PendingMessage> qualityQ = new LinkedBlockingQueue<>(CAPACITY);
    private final BlockingQueue<PendingMessage> rawQ     = new LinkedBlockingQueue<>(CAPACITY);

    private final AtomicLong droppedCount = new AtomicLong();

    private volatile boolean running = false;
    private Thread worker;

    private final RabbitTemplate rabbitTemplate;

    public synchronized void offer(PendingMessage pm) {
        if (pm == null){
            return;
        }
        while (rawQ.size() + qualityQ.size() >= CAPACITY) {
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

    @PostConstruct
    void registerConfirmCallback() {
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

    @Override
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "publish-retry-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("PublishRetryBuffer 시작");
    }

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