package site.omagotchi.ruleservice.writer.infrastructure;


import com.influxdb.client.BucketsApi;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.TasksApi;
import com.influxdb.client.domain.BucketRetentionRules;
import com.influxdb.client.domain.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
@Component
public class InfluxInitializer implements ApplicationRunner {
    private static final String DOWNSAMPLE_1M = "omagotchi-downsample-1m";
    private static final String DOWNSAMPLE_1H = "omagotchi-downsample-1h";
    private static final String DOWNSAMPLE_1D = "omagotchi-downsample-1d";


    private static final String FLUX_RESOURCE_1M = "flux/downsample-raw-to-1m.flux";
    private static final String FLUX_RESOURCE_1H = "flux/downsample-1m-to-1h.flux";
    private static final String FLUX_RESOURCE_1D = "flux/downsample-1h-to-1d.flux";

    private final InfluxDBClient client;
    private final InfluxDbProperties properties;


    @Override
    public void run(ApplicationArguments args){
        try{
            String orgId = properties.org();

            createBucket(orgId, properties.buckets().raw(), 7 * 24 * 3600);
            createBucket(orgId, properties.buckets().avg1m(), 30 * 24 * 3600);
            createBucket(orgId, properties.buckets().avg1h(), 365 * 24 * 3600);
            createBucket(orgId, properties.buckets().avg1d(), 0);

            createTask(orgId, DOWNSAMPLE_1M, FLUX_RESOURCE_1M, "1m");
            createTask(orgId, DOWNSAMPLE_1H, FLUX_RESOURCE_1H, "1h");
            createTask(orgId, DOWNSAMPLE_1D, FLUX_RESOURCE_1D, "1d");

        }catch (Exception e){
            log.warn("InfluxDB 초기화 실패", e);
        }
    }

    /** 버킷 생성. */
    private void createBucket(String orgId, String name, int retentionSeconds){
        BucketsApi api = client.getBucketsApi();
        if(api.findBucketByName(name) != null){
            return;
        }

        BucketRetentionRules rule = new BucketRetentionRules().everySeconds(retentionSeconds);
        api.createBucket(name, rule, orgId);
        log.info("버킷 생성:{} (TTL {}s)", name, retentionSeconds);
    }

    /** 태스크 생성. */
    private void createTask(String orgId, String name, String fluxResource, String every){
        TasksApi api = client.getTasksApi();

        for(Task task : api.findTasks()){
            if(name.equals(task.getName())){
                return;
            }
        }

        String flux = loadResource(fluxResource);
        Task task = api.createTaskEvery(name, flux, every, orgId);
        log.info("Task 생성: {} (every {}, id={})", name, every, task.getId());
    }

    /** 쿼리 로드. resources/flyx/path */
    private String loadResource(String path){
        try{
            return new String(
                    new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }catch (IOException e){
            throw new IllegalStateException("Flux 리소스 로드 실패: " + path, e);
        }
    }
}
