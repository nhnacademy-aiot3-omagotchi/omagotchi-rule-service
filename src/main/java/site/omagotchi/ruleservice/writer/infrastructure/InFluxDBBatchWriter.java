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

    private final int capacity;
    private final WriteApiBlocking writeApi;
    private final String orgId;
    private final String bucket;
    private final int batchSize;
    private final long flushIntervalMs;

    /** raw 버킷 하나만 적재하므로 단일 큐 */
    private final BlockingQueue<Point> queue = new LinkedBlockingQueue<>();
    private Thread worker;

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
        this.capacity = capacity;
    }

    /** 바로 쓰기를 하기보다는 배치를 위해 일단 버퍼에 적재 */
    public void offer(Point point){
        if(point == null){
            return;
        }

        while(queue.size() >= capacity){
            queue.poll();
            log.warn("버퍼 포화 -> 폐기");
        }

        queue.offer(point);
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

    /** 버퍼에서 batchSize만큼 꺼내 쓴다 */
    private void drainAndWrite(){
        List<Point> batch = new ArrayList<>(batchSize);

        while(!queue.isEmpty()){
            queue.drainTo(batch, batchSize);

            if(batch.isEmpty()){
                break;
            }

            if(!writeWithRetry(batch)){
                batch.forEach(this::offer);
                return;
            }
            batch.clear();
        }
    }

    /** 쓰기 실패 시 지수 백오프로 재시도, 완전 실패하면 버퍼에 보존 */
    private boolean writeWithRetry(List<Point> batch){
        long backoff = MIN_BACKOFF_MS;
        for(int i = 1; i <= MAX_RETRY; i++){
            try{
                writeApi.writePoints(bucket, orgId, batch); // 블로킹 - 완료 후 healthy 갱신
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
        log.error("InfluxDB 쓰기 -> 버퍼에 보존");
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

    public boolean isHealthy(){
        return healthy;
    }
}
