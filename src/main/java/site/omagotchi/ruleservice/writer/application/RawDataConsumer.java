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
import site.omagotchi.ruleservice.writer.infrastructure.InfluxDbBatchWriter;
import site.omagotchi.ruleservice.writer.infrastructure.InfluxDbProperties;

import java.io.IOException;
/**
 * raw메세지 소비자
 * 해당 메세지를 소비함과 동시에 InfluxDbBatchWriter를 사용해서 Influx쓰기 실행*/
@Slf4j
@Component
public class RawDataConsumer {

    private final InfluxDbBatchWriter batchWriter;
    private final String rawBucket;

    private final Counter enqueued;
    private final Counter requeued;
    private final Counter failed;

    public RawDataConsumer(InfluxDbBatchWriter batchWriter,
                           InfluxDbProperties properties,
                           MeterRegistry registry){

        this.batchWriter = batchWriter;
        this.rawBucket = properties.buckets().raw();
        this.enqueued = registry.counter("influx.raw.cousumed");
        this.requeued = registry.counter("influx.raw.requeued");
        this.failed = registry.counter("influx.raw.failed");
    }
    @RabbitListener(
            queues = RabbitTopologyConfig.QUEUE_RAW, // raw 큐에 메세지가 적재된다면
            concurrency = "2-8", //2~8개의 쓰레드를 사용해
            ackMode = "MANUAL" // 수동 ack모드를 통해 메세지를 소비
    )
    public void consume(
            SensorReading reading,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException { // tag=메세지의 순번. 수동 ack모드에서 필요함.

        if(reading.traceId() != null){
            MDC.put("traceId", reading.traceId());
        }

        try{
            if(!batchWriter.isHealthy()){ // InfluxDB 장애. raw 큐에 재적재
                channel.basicNack(tag, false, true);
                requeued.increment();
                return;
            }

            batchWriter.offer(rawBucket, toPoint(reading)); //논 블로킹 작업
            channel.basicAck(tag, false);
            enqueued.increment();

        }catch (Exception e){ //메세지 자체를 처리못함 (재전송이 의미가 없음) DLQ 전송
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
