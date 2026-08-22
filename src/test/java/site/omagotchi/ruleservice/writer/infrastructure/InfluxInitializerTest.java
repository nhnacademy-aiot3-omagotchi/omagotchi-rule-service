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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class InfluxInitializerTest {

    private static final String ORG   = "omagotchi";
    private static final String TOKEN = "test-token";
    private static InfluxDbProperties properties;

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
        properties = new InfluxDbProperties(
                influx.getUrl(), TOKEN, orgId,
                new InfluxDbProperties.Buckets(
                        "omagotchi-raw", "omagotchi-avg-1h", "omagotchi-avg-1d"),
                new InfluxDbProperties.Retention(7, 365, 0)
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

    @Test
    @DisplayName("태스크에 지각 데이터 흡수용 offset이 설정된다")
    void createsTasksWithOffset() {
        List<Task> tasks = client.getTasksApi().findTasks();

        Task hourly = tasks.stream()
                .filter(task -> "omagotchi-downsample-1h".equals(task.getName()))
                .findFirst().orElseThrow();
        Task daily = tasks.stream()
                .filter(task -> "omagotchi-downsample-1d".equals(task.getName()))
                .findFirst().orElseThrow();

        assertEquals("5m", hourly.getOffset());
        assertEquals("15m", daily.getOffset());
    }

    @Test
    @DisplayName("일 집계는 한국 자정 기준으로 하루를 자른다")
    void dailyTaskUsesSeoulTimezone() {
        Task daily = client.getTasksApi().findTasks().stream()
                .filter(task -> "omagotchi-downsample-1d".equals(task.getName()))
                .findFirst().orElseThrow();

        assertTrue(daily.getFlux().contains("Asia/Seoul"),
                "일 집계 flux에 시간대 지정이 없습니다");
    }

    @Test
    @DisplayName("flux 내용이 바뀌면 기존 태스크를 갱신한다")
    void updatesTaskWhenFluxChanged() {
        Task before = client.getTasksApi().findTasks().stream()
                .filter(task -> "omagotchi-downsample-1h".equals(task.getName()))
                .findFirst().orElseThrow();

        // 서버 쪽 내용을 일부러 다르게 만든 뒤
        before.setFlux(before.getFlux().replace("offset: 5m", "offset: 1m"));
        client.getTasksApi().updateTask(before);

        // 다시 기동하면 코드 내용으로 되돌아와야 한다
        new InfluxInitializer(client, /* setUp과 같은 properties */ properties).run(null);

        Task after = client.getTasksApi().findTasks().stream()
                .filter(task -> "omagotchi-downsample-1h".equals(task.getName()))
                .findFirst().orElseThrow();
        assertEquals("5m", after.getOffset());
    }
}