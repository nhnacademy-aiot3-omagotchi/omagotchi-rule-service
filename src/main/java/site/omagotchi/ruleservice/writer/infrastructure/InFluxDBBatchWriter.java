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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
    private final InfluxDbProperties properties;

    /** [버킷 이름, 데이터(point)]*/
    private final Map<String, BlockingQueue<Point>> queues = new ConcurrentHashMap<>();
    private Thread worker;

    private volatile boolean running = false;
    private volatile boolean healthy = true;

    @Autowired
    public InfluxDbBatchWriter(InfluxDBClient client, InfluxDbProperties properties){
        this(client, properties, DEFAULT_CAPACITY);
    }
    public InfluxDbBatchWriter(InfluxDBClient client, InfluxDbProperties properties, int capacity){
        this.writeApi = client.getWriteApiBlocking();
        this.properties = properties;
        this.capacity = capacity;
    }


    public void offer(String bucket, Point point){
        if(point == null){
            return;
        }

        BlockingQueue<Point> points = queues.computeIfAbsent(bucket, b -> new LinkedBlockingQueue<>());
        while(points.size() >= capacity){
            points.poll();
            log.warn("버퍼 포화 -> 페기, bucket={}", bucket);
        }

        points.offer(point);
    }


    @Override
    public void start() {
        running = true;
        worker = new Thread(this::runLoop);
        worker.setDaemon(true);
        worker.start();
        log.info("InfluxDBBatchWriter 시작");
    }
    private void runLoop(){
        long interval = properties.batch().flushIntervalMs();
        while(running){
            try{
                Thread.sleep(interval);
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }

            for(String bucket : queues.keySet()){
                drainAndWrite(bucket);
            }
        }
    }

    private void drainAndWrite(String bucket){
        int batchSize = properties.batch().size();

        BlockingQueue<Point> queue = queues.get(bucket);

        List<Point> batch = new ArrayList<>(batchSize);

        while(!queue.isEmpty()){
            queue.drainTo(batch, batchSize);

            if(batch.isEmpty()){
                break;
            }
            if(!writeWithRetry(bucket, batch)){
                batch.forEach(queue::offer);
                return;
            }
            batch.clear();
        }

    }

    private boolean writeWithRetry(String bucket, List<Point> batch){
        long backoff = MIN_BACKOFF_MS;
        for(int i = 1; i <= MAX_RETRY; i++){
            try{
                writeApi.writePoints(bucket, properties.org(), batch);
                healthy = true;
                return true;
            }catch (Exception e){
                healthy = false;
                log.warn("InfluxDB 쓰기 실패 (bucket={}, {}/{}회): {}",
                        bucket, i, MAX_RETRY, e.getMessage());
                try{
                    Thread.sleep(backoff);
                }catch (InterruptedException ex){
                    Thread.currentThread().interrupt();
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
        log.error("InfluxDB 쓰기 -> 버퍼에 보존 bucket={}", bucket);
        return false;
    }

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

            for(String buket : queues.keySet()){
                drainAndWrite(buket);
            }
        }
        log.info("InfluxDBBatchWriter 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isHealthy(){
        return healthy;
    }
}
