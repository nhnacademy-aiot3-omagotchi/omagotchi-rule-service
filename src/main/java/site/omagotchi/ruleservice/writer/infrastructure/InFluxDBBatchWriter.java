package site.omagotchi.ruleservice.writer.infrastructure;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class InfluxDbBatchWriter implements SmartLifecycle {
    private static final int DEFAULT_CAPACITY = 100_000;
    private static final int MAX_RETRY = 5;
    private static final long MIN_BACKOFF_MS = 200;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final WriteApiBlocking writeApi;
    private final String orgId;
    private final String bucket;
    private final int batchSize;
    private final long flushIntervalMs;

    /** raw 버킷 하나만 적재하므로 단일 큐. 용량 제한 큐라 포화 시 offer가 원자적으로 거절된다 */
    private final BlockingQueue<Point> queue;
    private Thread worker;

    /** 쓰기 실패 배치. 큐에 재삽입하면 포화 시 이미 ACK된 데이터가 거절·유실될 수 있어
     *  워커가 직접 보유하고 성공할 때까지 재시도한다 (워커 스레드 전용, stop()은 join 후 접근) */
    private List<Point> pendingBatch;

    private volatile boolean running = false;
    private volatile boolean healthy = true;

    @Autowired
    public InfluxDbBatchWriter(InfluxDBClient client, InfluxDbProperties properties){
        this(client, properties, DEFAULT_CAPACITY);
    }

    public InfluxDbBatchWriter(InfluxDBClient client, InfluxDbProperties properties, int capacity){
        this.writeApi = client.getWriteApiBlocking();
        this.orgId = properties.org();
        this.bucket = properties.buckets().raw();
        this.batchSize = properties.batch().size();
        this.flushIntervalMs = properties.batch().flushIntervalMs();
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * 바로 쓰기를 하기보다는 배치를 위해 일단 버퍼에 적재.
     * 버퍼 포화 시 기존(이미 ACK된) 데이터를 보존하기 위해 신규 적재를 거절한다.
     * @return 적재 성공 여부. false면 호출자가 해당 delivery를 재큐잉해야 한다
     */
    public boolean offer(Point point){
        if(point == null){
            return true;
        }
        return queue.offer(point);
    }

    /** 애플리케이션 가동 시 start() 호출, 데몬 스레드에서 runLoop() 실행 */
    @Override
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "influx-batch-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("InfluxDbBatchWriter 시작");
    }

    /** 배치 쓰기 루프 */
    private void runLoop(){
        while(running){
            try{
                Thread.sleep(flushIntervalMs);
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }

            drainAndWrite();
        }
    }

    /** 보유 중인 실패 배치부터 재시도한 뒤, 버퍼에서 batchSize만큼 꺼내 쓴다 */
    private void drainAndWrite(){
        if(pendingBatch != null){
            if(!writeWithRetry(pendingBatch)){
                return; // 실패 배치가 성공하기 전엔 신규 배치를 진행하지 않음 (순서 보존)
            }
            pendingBatch = null;
        }

        while(!queue.isEmpty()){
            List<Point> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);

            if(batch.isEmpty()){
                break;
            }

            if(!writeWithRetry(batch)){
                pendingBatch = batch;
                return;
            }
        }
    }

    /** 쓰기 실패 시 지수 백오프로 재시도, 완전 실패하면 버퍼에 보존 */
    private boolean writeWithRetry(List<Point> batch){
        long backoff = MIN_BACKOFF_MS;
        for(int i = 1; i <= MAX_RETRY; i++){
            try{
                writeApi.writePoints(bucket, orgId, List.copyOf(batch)); // 블로킹 - 완료 후 healthy 갱신. 외부에 불변 스냅샷 전달
                healthy = true;
                return true;
            }catch (Exception e){
                healthy = false;
                log.warn("InfluxDB 쓰기 실패 ({}/{}회): {}", i, MAX_RETRY, e.getMessage());
                try{
                    Thread.sleep(backoff);
                }catch (InterruptedException ex){
                    Thread.currentThread().interrupt();
                    break; // 인터럽트(셧다운) 시 재시도 즉시 중단 → 워커 종료
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
        log.error("InfluxDB 쓰기 실패 - 배치를 보유하고 다음 주기에 재시도");
        return false;
    }

    /** 앱 종료 시 stop() 호출, 남은 데이터 최종 flush 후 종료 */
    @Override
    public void stop() {
        running = false;
        if(worker != null){
            worker.interrupt();
            try{
                worker.join(5_000);
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
            }

            if(worker.isAlive()){
                log.warn("워커가 5초 내 미종료 - 최종 flush 생략(동시 접근 방지)");
            }else{
                drainAndWrite(); // 워커가 완전히 끝났을 때만 최종 flush
            }
        }
        log.info("InfluxDbBatchWriter 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 리스너 컨테이너(기본 phase=MAX_VALUE)보다 먼저 시작하고 나중에 종료되도록 낮은 phase 지정.
     *  종료 시 리스너가 먼저 멈춰야 최종 flush 이후에 큐로 Point가 유입되지 않는다 */
    @Override
    public int getPhase() {
        return 0;
    }

    /** 한계: 초기값이 true라 InfluxDB가 기동 시점부터 접속 불가여도 첫 쓰기 실패 전까지는
     *  게이트를 통과해 ACK될 수 있다. ACK-후-영속화 전환(후속 PR)에서 해결 예정 */
    public boolean isHealthy(){
        return healthy;
    }
}
