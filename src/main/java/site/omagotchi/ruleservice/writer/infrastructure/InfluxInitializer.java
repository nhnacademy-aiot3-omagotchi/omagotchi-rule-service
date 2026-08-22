package site.omagotchi.ruleservice.writer.infrastructure;


import com.influxdb.client.BucketsApi;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.TasksApi;
import com.influxdb.client.domain.BucketRetentionRules;
import com.influxdb.client.domain.Task;
import com.influxdb.client.domain.TaskCreateRequest;
import com.influxdb.client.domain.TaskStatusType;
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
    private static final String DOWNSAMPLE_1H = "omagotchi-downsample-1h";
    private static final String DOWNSAMPLE_1D = "omagotchi-downsample-1d";

    private static final String FLUX_RESOURCE_1H = "flux/downsample-raw-to-1h.flux";
    private static final String FLUX_RESOURCE_1D = "flux/downsample-1h-to-1d.flux";

    private final InfluxDBClient client;
    private final InfluxDbProperties properties;


    @Override
    public void run(ApplicationArguments args){

        String orgId = properties.org();

        createBucket(orgId, properties.buckets().raw(), properties.retention().rawDays() * 24 * 3600);
        createBucket(orgId, properties.buckets().avg1h(),  properties.retention().avg1hDays() *  24 * 3600);
        createBucket(orgId, properties.buckets().avg1d(), properties.retention().avg1dDays() * 24 * 3600);

        createTask(orgId, DOWNSAMPLE_1H, FLUX_RESOURCE_1H);
        createTask(orgId, DOWNSAMPLE_1D, FLUX_RESOURCE_1D);

    }

    /** 버킷 생성. */
    private void createBucket(String orgId, String name, int retentionSeconds){
        try{
            BucketsApi api = client.getBucketsApi();

            if(api.findBucketByName(name) != null){
                return;
            }

            BucketRetentionRules rule = new BucketRetentionRules().everySeconds(retentionSeconds);
            api.createBucket(name, rule, orgId);
            log.info("Bucket 생성:{} (TTL {}s)", name, retentionSeconds);
        }catch (Exception e){
            log.warn("Bucket 생성 실패: {} : {}", name, e.getMessage());
        }
    }

    /** 태스크 생성. */
    private void createTask(String orgId, String name, String fluxResource){
        try{
            TasksApi api = client.getTasksApi();

            for(Task task : api.findTasks()){
                if(name.equals(task.getName())){
                    return;
                }
            }

            String flux = resolveBuckets(loadResource(fluxResource));
            if (!flux.contains("name: \"" + name + "\"")) {
                log.warn("Task 생성 건너뜀: {} — flux의 option task 이름과 불일치", name);
                return;
            }

            TaskCreateRequest request = new TaskCreateRequest();
            request.setOrgID(orgId);
            request.setFlux(flux);
            request.setStatus(TaskStatusType.ACTIVE);

            Task task = api.createTask(request);
            log.info("Task 생성: {} (every {}, id={})", name, task.getId());
        }catch (Exception e){
            log.warn("Task 생성 실패: {} : {}", name, e.getMessage());
        }
    }

    /** flux의 버킷 플레이스홀더를 설정값(properties.buckets)으로 치환 — 환경별 버킷 이름 대응 */
    private String resolveBuckets(String flux){
        return flux
                .replace("${rawBucket}",   properties.buckets().raw())
                .replace("${avg1hBucket}", properties.buckets().avg1h())
                .replace("${avg1dBucket}", properties.buckets().avg1d());
    }

    /** 쿼리 로드. resources/flux/path */
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