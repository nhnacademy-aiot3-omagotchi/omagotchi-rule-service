package site.omagotchi.ruleservice.writer.application;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.recovery.application.RawFailureTracker;
import site.omagotchi.ruleservice.writer.infrastructure.InfluxDbProperties;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RawDataConsumerTest {
    private static final String ORG = "org-id";
    private static final String BUCKET = "omagotchi-raw";

    @Mock
    InfluxDBClient client;

    @Mock
    WriteApiBlocking writeApi;

    @Mock
    RawFailureTracker tracker;

    SimpleMeterRegistry registry;

    RawDataConsumer consumer;

    SensorReading reading;

    @BeforeEach
    void setUp(){
        when(client.getWriteApiBlocking()).thenReturn(writeApi);   // ← 대입이 아니라 스텁

        registry = new SimpleMeterRegistry();

        InfluxDbProperties properties = new InfluxDbProperties(
                "http://localhost:8086", "token", ORG,
                new InfluxDbProperties.Buckets(BUCKET, "omagotchi-avg-1h", "omagotchi-avg-1d"),
                new InfluxDbProperties.Retention(7, 365, 0)
        );

        consumer = new RawDataConsumer(client, properties, registry, tracker);

        reading = new SensorReading(
                "test-traceId",
                "lab", "p1", "eui-1", "temperature",
                25.5,
                Instant.ofEpochMilli(1_700_000_000_000L),
                Instant.ofEpochMilli(1_700_000_000_500L),
                "sensor-1",
                7L
        );
    }


    @Test
    @DisplayName("정상 소비 - raw 버킷에 쓰고 consumed 카운터 증가")
    void successConsumeTest(){
        consumer.consume(reading);

        verify(writeApi).writePoint(eq(BUCKET), eq(ORG), any(Point.class));
        assertEquals(1.0, registry.get("influx.raw.consumed").counter().count());
    }

    @Test
    @DisplayName("쓰기 실패 - 재시도/DLQ 처리를 위해 예외 전파")
    void failConsumeTest(){
        RuntimeException failure = new RuntimeException("influx down");
        doThrow(failure).when(writeApi).writePoint(anyString(), anyString(), any(Point.class));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> consumer.consume(reading));

        assertSame(failure, thrown);
        assertEquals(0.0, registry.get("influx.raw.consumed").counter().count());
    }

    @Test
    @DisplayName("Point 매핑 - 태크/필드/타임스탬프가 라인프로토콜에 반영")
    void pointMappingTest(){
        consumer.consume(reading);

        verify(writeApi).writePoint(eq(BUCKET), eq(ORG), assertArg(point ->
                assertEquals(
                        "temperature,device_eui=eui-1,location=lab,point=p1"
                                + " received_at=1700000000500i,value=25.5 1700000000000",
                        point.toLineProtocol()
                )
        ));
    }

}