package site.omagotchi.ruleservice.writer.application;


import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;
import site.omagotchi.ruleservice.writer.infrastructure.InFluxDBBatchWriter;
import site.omagotchi.ruleservice.writer.infrastructure.InfluxDBProperties;

import java.awt.*;
import java.io.IOException;

@Slf4j
@Component
public class RawDataConsumer {

    private final InFluxDBBatchWriter batchWriter;
    private final String rawBucket;

    private final Counter enqueued;
    private final Counter requeued;
    private final Counter failed;

    public RawDataConsumer(InFluxDBBatchWriter batchWriter,
                           InfluxDBProperties properties,
                           MeterRegistry registry){

        this.batchWriter = batchWriter;
        this.rawBucket = properties.buckets().raw();
        this.enqueued = registry.counter("influx.raw.cousumed");
        this.requeued = registry.counter("influx.raw.requeued");
        this.failed = registry.counter("influx.raw.failed");
    }
    @RabbitListener(
            queues = RabbitTopologyConfig.QUEUE_RAW,
            concurrency = "2-8",
            ackMode = "MANUAL"
    )
    public void consume(
            SensorReading reading,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {

        if(reading.traceId() != null){
            MDC.put("traceId", reading.traceId());
        }

        try{
            if(!batchWriter.isHealthy()){
                channel.basicNack(tag, false, true);
                requeued.increment();
                return;
            }
            batchWriter.offer(rawBucket, toPoint(reading));
            channel.basicAck(tag, false);
            enqueued.increment();

        }catch (Exception e){
            failed.increment();
            log.error("raw 소비 실패 -> DLQ, deviceEui={}, measurment={}", reading.deviceEui(), reading.measurement(), e);
            channel.basicNack(tag, false, false);
        }finally{
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
