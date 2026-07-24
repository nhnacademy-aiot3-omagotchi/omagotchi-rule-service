package site.omagotchi.ruleservice.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;
import site.omagotchi.ruleservice.quality.LastSeenRegistry;
import site.omagotchi.ruleservice.quality.QualityEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

public class NormalizerNode extends AbstractNode {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LastSeenRegistry lastSeenRegistry;

    public NormalizerNode(String id, LastSeenRegistry lastSeenRegistry) {
        super(id);
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("invalid");
        this.lastSeenRegistry = lastSeenRegistry;
    }

    @Override
    protected void onProcess(Message message) {
        //topic 파싱
        String topic = message.get("topic");
        String[] topicSegments = topic.split("/");

        String location;
        String point;
        String deviceEui;
        String measurement;

        if (topicSegments[0].equals("iot")){
            if (topicSegments.length < 6) {
                QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(),"iot 토픽 세그먼트 부족: " + topic);
                send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
                return;
            }
            location = topicSegments[1];
            point = topicSegments[2];
            deviceEui = topicSegments[4];
            measurement = topicSegments[5];

        } else if (topicSegments[0].equals("modbus")) {
            if (topicSegments.length < 2) {
                QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(),"modbus 토픽 세그먼트 부족: " + topic);
                send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
                return;
            }
            location = "modbus";
            point = "gateway";
            deviceEui = "modbus"; //missingDetector에 이용
            measurement = topicSegments[1];

        } else {
            QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(),"알 수 없는 토픽 형식: " + topic);
            send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
            return;
        }

        //raw 파싱
        String raw = message.get("raw");

//        String[] rawSegments = raw.split(",");
//
//        double value = Double.parseDouble(rawSegments[0]);
//        Instant measuredAt = Instant.parse(rawSegments[1]);
//        Instant receivedAt = Instant.parse(rawSegments[2]);
//        String deviceName = rawSegments[4];

        try {
            JsonNode node = objectMapper.readTree(raw);
            boolean timeSubstituted = false;

            if (node.get("value") == null) {
                QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(),"value: 누락");
                send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
                return;
            }
            double value = node.get("value").asDouble();
            Instant receivedAt = message.get("receivedAt");

            JsonNode timeNode = node.get("time") != null ? node.get("time") : node.get("timestamp");

            Instant measuredAt;
            if (timeNode != null) {
                measuredAt = Instant.parse(timeNode.asText());
            } else {
                measuredAt = receivedAt;
                timeSubstituted = true;
            }

            JsonNode deviceNameNode = node.get("device_name");
            String deviceName = deviceNameNode != null ? deviceNameNode.asText() : null;

            //SensorReading 조립
            SensorReading sensorReading = new SensorReading(message.getTraceId(), location, point, deviceEui, measurement
                    , value, measuredAt, receivedAt, deviceName);
            //수신 기록
            lastSeenRegistry.update(sensorReading.deviceEui(),sensorReading.measurement(),sensorReading.receivedAt());

            send("out",Message.of(message.getTraceId(), Map.of("sensorReading",sensorReading, "_timeSubstituted",timeSubstituted)));
        } catch (JsonProcessingException | DateTimeParseException e) {
            QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(),"payload 파싱 실패: " + e.getMessage());
            send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
        }
    }

}
