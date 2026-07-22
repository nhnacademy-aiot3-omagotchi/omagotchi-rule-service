package site.omagotchi.ruleservice.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.core.node.AbstractNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

public class NormalizerNode extends AbstractNode {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NormalizerNode(String id) {
        super(id);
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("invalid");
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
                send("invalid", message.withEntry("reason", "iot 토픽 세그먼트 부족: " + topic));
                return;
            }
            location = topicSegments[1];
            point = topicSegments[2];
            deviceEui = topicSegments[4];
            measurement = topicSegments[5];

        } else if (topicSegments[0].equals("modbus")) {
            if (topicSegments.length < 2) {
                send("invalid", message.withEntry("reason", "modbus 토픽 세그먼트 부족: " + topic));
                return;
            }
            location = "modbus";
            point = "gateway";
            deviceEui = null;
            measurement = topicSegments[1];

        } else {
            send("invalid", message.withEntry("reason", "알 수 없는 토픽 형식: " + topic));
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
                send("invalid", message.withEntry("reason", "value 누락"));
                return;
            }
            double value = node.get("value").asDouble();
            Instant receivedAt = message.get("receivedAt");
            Instant measuredAt;
            if (node.get("time") != null) {
                measuredAt = Instant.parse(node.get("time").asText());
            } else {
                measuredAt = receivedAt;
                timeSubstituted = true;
            }
            String deviceName = node.get("device_name").asText();

            //SensorReading 조립
            SensorReading sensorReading = new SensorReading(message.getTraceId(), location, point, deviceEui, measurement
                    , value, measuredAt, receivedAt, deviceName);

            send("out",Message.of(message.getTraceId(), Map.of("sensorReading",sensorReading, "_timeSubstituted",timeSubstituted)));
        } catch (JsonProcessingException | DateTimeParseException e) {
            send("invalid", message.withEntry("reason", "payload 파싱 실패: " + e.getMessage()));
        }
    }

}
