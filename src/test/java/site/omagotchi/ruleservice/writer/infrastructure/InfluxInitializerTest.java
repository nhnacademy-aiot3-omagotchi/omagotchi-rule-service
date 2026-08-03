package site.omagotchi.ruleservice.writer.infrastructure;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class InfluxInitializerTest {

    private static final String ORG   = "omagotchi";
    private static final String TOKEN = "test-token";

    @Container
    static InfluxDBContainer<?> influx =
            new InfluxDBContainer<>(DockerImageName.parse("influxdb:2.7"))
                    .withOrganization(ORG)
                    .withBucket("primary")
                    .withAdminToken(TOKEN);

    static InfluxDBClient client;

    @BeforeAll
    static void setUp() {
        client = InfluxDBClientFactory.create(influx.getUrl(), TOKEN.toCharArray());

        // 컨테이너가 만든 org의 실제 ID 조회
        String orgId = client.getOrganizationsApi().findOrganizations().stream()
                .filter(o -> ORG.equals(o.getName()))
                .findFirst().orElseThrow()
                .getId();

        // initializer가 필요로 하는 값만 직접 구성
        InfluxDbProperties properties = new InfluxDbProperties(
                influx.getUrl(), TOKEN, orgId,
                new InfluxDbProperties.Buckets(
                        "omagotchi-raw", "omagotchi-avg-1h", "omagotchi-avg-1d"),
                new InfluxDbProperties.Batch(1000, 1000)
        );

        // 검증 대상 실행
        new InfluxInitializer(client, properties).run(null);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    @Test
    @DisplayName("버킷 3개가 생성된다")
    void createsBuckets() {
        List<String> names = client.getBucketsApi().findBuckets().stream()
                .map(Bucket::getName).toList();
        assertTrue(names.containsAll(List.of(
                        "omagotchi-raw", "omagotchi-avg-1h", "omagotchi-avg-1d")),
                "생성된 버킷: " + names);
    }

    @Test
    @DisplayName("다운샘플링 태스크 2개가 생성된다")
    void createsTasks() {
        List<String> taskNames = client.getTasksApi().findTasks().stream()
                .map(Task::getName).toList();
        assertTrue(taskNames.containsAll(List.of(
                        "omagotchi-downsample-1h", "omagotchi-downsample-1d")),
                "생성된 태스크: " + taskNames);
    }
}