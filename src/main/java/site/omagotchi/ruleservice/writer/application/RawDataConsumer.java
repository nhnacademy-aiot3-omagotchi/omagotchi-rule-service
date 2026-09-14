package site.omagotchi.ruleservice.writer.application;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;
import site.omagotchi.ruleservice.recovery.application.RawFailureTracker;
import site.omagotchi.ruleservice.writer.infrastructure.InfluxDbProperties;

@Slf4j
@Component
public class RawDataConsumer {
    private static final String PIPELINE_CORRELATION_ID = "pipeline.correlation.id";

    private final WriteApiBlocking writeApi;
    private final RawFailureTracker tracker;
    private final String orgId;
    private final String bucket;

    private final Counter deliveryAttempts;
    private final Counter consumed;
    private final ObservationRegistry observationRegistry;

    public RawDataConsumer(
            InfluxDBClient client,
            InfluxDbProperties properties,
            MeterRegistry registry,
            RawFailureTracker tracker,
            ObservationRegistry observationRegistry
    ) {
        this.writeApi = client.getWriteApiBlocking();
        this.tracker = tracker;
        this.observationRegistry = observationRegistry;
        this.orgId = properties.org();
        this.bucket = properties.buckets().raw();
        this.deliveryAttempts = registry.counter("raw.consumer.delivery.attempts");
        this.consumed = registry.counter("influx.raw.consumed");
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_RAW)
    public void consume(SensorReading reading) {
        deliveryAttempts.increment();

        String previousCorrelationId = MDC.get(PIPELINE_CORRELATION_ID);
        if (reading.traceId() == null) {
            MDC.remove(PIPELINE_CORRELATION_ID);
        } else {
            MDC.put(PIPELINE_CORRELATION_ID, reading.traceId());
        }

        try {
            // Rabbit 소비 Span 아래 실제 적재 시간 측정, 센서 원본·기기 식별자 제외
            Observation.createNotStarted("influxdb.write", observationRegistry)
                    .observe(() -> writeApi.writePoint(bucket, orgId, toPoint(reading)));
            tracker.onSuccess();
            consumed.increment();
        } finally {
            if (previousCorrelationId == null) {
                MDC.remove(PIPELINE_CORRELATION_ID);
            } else {
                MDC.put(PIPELINE_CORRELATION_ID, previousCorrelationId);
            }
        }
    }

    private Point toPoint(SensorReading reading) {
        return Point.measurement(reading.measurement())
                .addTag("device_eui", reading.deviceEui())
                .addTag("location", reading.location())
                .addTag("point", reading.point())
                .addField("value", reading.value())
                .addField("received_at", reading.receivedAt().toEpochMilli())
                .time(reading.measuredAt(), WritePrecision.MS);
    }
}
