package site.omagotchi.ruleservice.writer.application;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;
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
    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
    private static final String PIPELINE_CORRELATION_ID = "pipeline.correlation.id";

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
    void setUp() {
        when(client.getWriteApiBlocking()).thenReturn(writeApi);

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

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("정상 소비 - raw 버킷에 쓰고 consumed 카운터 증가")
    void successConsumeTest() {
        consumer.consume(reading);

        verify(writeApi).writePoint(eq(BUCKET), eq(ORG), any(Point.class));
        assertEquals(1.0, registry.get("influx.raw.consumed").counter().count());
        assertEquals(1.0, registry.get("raw.consumer.delivery.attempts").counter().count());
    }

    @Test
    @DisplayName("쓰기 실패 - 재시도/DLQ 처리를 위해 예외 전파")
    void failConsumeTest() {
        RuntimeException failure = new RuntimeException("influx down");
        doThrow(failure).when(writeApi).writePoint(anyString(), anyString(), any(Point.class));
        MDC.put(RequestIdContext.MDC_KEY, REQUEST_ID);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> consumer.consume(reading));

        assertSame(failure, thrown);
        assertEquals(0.0, registry.get("influx.raw.consumed").counter().count());
        assertEquals(1.0, registry.get("raw.consumer.delivery.attempts").counter().count());
        assertNull(MDC.get(PIPELINE_CORRELATION_ID));
        assertEquals(REQUEST_ID, MDC.get(RequestIdContext.MDC_KEY));
    }

    @Test
    @DisplayName("Point 매핑 - 태그/필드/타임스탬프가 라인프로토콜에 반영")
    void pointMappingTest() {
        consumer.consume(reading);

        verify(writeApi).writePoint(eq(BUCKET), eq(ORG), assertArg(point ->
                assertEquals(
                        "temperature,device_eui=eui-1,location=lab,point=p1"
                                + " received_at=1700000000500i,value=25.5 1700000000000",
                        point.toLineProtocol()
                )
        ));
    }

    @Test
    @DisplayName("RabbitMQ Pipeline ID는 별도 MDC에 두고 HTTP Request ID는 보존")
    void keepsPipelineAndHttpRequestContextsSeparate() {
        MDC.put(PIPELINE_CORRELATION_ID, "outer-pipeline");
        MDC.put(RequestIdContext.MDC_KEY, REQUEST_ID);

        doAnswer(invocation -> {
            assertEquals(reading.traceId(), MDC.get(PIPELINE_CORRELATION_ID));
            assertEquals(REQUEST_ID, MDC.get(RequestIdContext.MDC_KEY));
            return null;
        }).when(writeApi).writePoint(eq(BUCKET), eq(ORG), any(Point.class));

        consumer.consume(reading);

        assertEquals("outer-pipeline", MDC.get(PIPELINE_CORRELATION_ID));
        assertEquals(REQUEST_ID, MDC.get(RequestIdContext.MDC_KEY));
    }
}
