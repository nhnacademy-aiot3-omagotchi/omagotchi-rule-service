package site.omagotchi.ruleservice.writer.application;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;
import site.omagotchi.ruleservice.writer.infrastructure.InfluxDbProperties;

@Slf4j
@Component
public class RawDataConsumer {
    private final WriteApiBlocking writeApi;
    private final String orgId;
    private final String bucket;

    private final Counter consumed;

    public RawDataConsumer(InfluxDBClient client, InfluxDbProperties properties, MeterRegistry registry){
        this.writeApi = client.getWriteApiBlocking();
        this.orgId = properties.org();
        this.bucket = properties.buckets().raw();
        this.consumed = registry.counter("influx.raw.consumed");
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_RAW)
    public void consume(SensorReading reading){
        if(reading.traceId() != null){
            MDC.put("traceId", reading.traceId());
        }

        try{
            writeApi.writePoint(bucket, orgId, toPoint(reading));
            consumed.increment();
        }catch (Exception e){
            log.error("raw 쓰기 실패 -> 재시도/DLQ, deviceEui={}, measurement={}", reading.deviceEui(), reading.measurement(), e);
            throw e;
        }finally {
            MDC.remove("traceId");
        }
    }

    private Point toPoint(SensorReading reading){
        return Point.measurement(reading.measurement())
                .addTag("device_eui", reading.deviceEui())
                .addTag("location", reading.location())
                .addTag("point", reading.point())
                .addField("value", reading.value())
                .addField("received_at", reading.receivedAt().toEpochMilli())
                .time(reading.measuredAt(), WritePrecision.MS);
    }
}
