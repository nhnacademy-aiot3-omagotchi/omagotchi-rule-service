package site.omagotchi.ruleservice.inbound.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.quality.domain.LastSeenRegistry;
import site.omagotchi.ruleservice.quality.domain.QualityEvent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;

/**
 * ChirpStack v4 uplink JSON을 측정항목별 SensorReading으로 정규화한다.
 * 프레임 하나(object N항목)가 N개의 SensorReading으로 분해되며 traceId를 공유한다.
 */
@Slf4j
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
        String raw = message.get("raw");
        Instant receivedAt = message.get("receivedAt");

        try {
            JsonNode root = objectMapper.readTree(raw);

            //필수 구조 확인
            JsonNode deviceInfo = root.get("deviceInfo");
            JsonNode object = root.get("object");
            if (deviceInfo == null || object == null || !object.isObject()) {
                log.warn("[무효] deviceInfo/object 누락: raw={}", raw);
                QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(), "deviceInfo/object 누락");
                send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
                return;
            }

            //디바이스 정보
            JsonNode devEuiNode = deviceInfo.get("devEui");
            if (devEuiNode == null || devEuiNode.isNull()) {
                log.warn("[무효] devEui 누락: raw={}", raw);
                QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(), "devEui 누락");
                send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
                return;
            }
            String deviceEui = devEuiNode.asText();

            JsonNode deviceNameNode = deviceInfo.get("deviceName");
            String deviceName = (deviceNameNode == null || deviceNameNode.isNull()) ? null : deviceNameNode.asText();

            //tags - 태그 자체가 없거나 point만 없는 센서가 있다 -> 회의실
            String location = "unknown";
            String point = null;
            JsonNode tags = deviceInfo.get("tags");
            if (tags != null) {
                JsonNode locationNode = tags.get("location");
                if (locationNode != null && !locationNode.isNull()) {
                    location = locationNode.asText();
                }
                JsonNode pointNode = tags.get("point");
                if (pointNode != null && !pointNode.isNull()) {
                    point = pointNode.asText();
                }
            }

            //프레임 순번 - 중복/유실 판정에 쓰인다
            JsonNode fCntNode = root.get("fCnt");
            Long fCnt = (fCntNode != null && fCntNode.canConvertToLong()) ? fCntNode.asLong() : null;

            //측정 시각 - 게이트웨이 시계(최상위 time, gwTime)는 신뢰하지 않고, ChirpStack 서버 시계(nsTime)의 최솟값을 쓴다
            Instant measuredAt = null;
            JsonNode rxInfo = root.get("rxInfo");
            if (rxInfo != null && rxInfo.isArray()) {
                for (JsonNode rx : rxInfo) {
                    JsonNode nsTimeNode = rx.get("nsTime");
                    if (nsTimeNode == null || nsTimeNode.isNull()) {
                        continue;
                    }
                    //Z와 +00:00 오프셋 형식을 모두 수용 (Instant.parse는 Z만 받는다)
                    Instant candidate = OffsetDateTime.parse(nsTimeNode.asText()).toInstant();
                    if (measuredAt == null || candidate.isBefore(measuredAt)) {
                        measuredAt = candidate;
                    }
                }
            }
            boolean timeSubstituted = false;
            if (measuredAt == null) {
                measuredAt = receivedAt;
                timeSubstituted = true;
            }

            //object 순회 - measurement마다 SensorReading 생성
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String measurement = field.getKey();
                JsonNode valueNode = field.getValue();

                double value;
                if ("magnet_status".equals(measurement)) {
                    //도어 센서: open/close 문자열을 door 1/0으로
                    measurement = "door";
                    value = "open".equals(valueNode.asText()) ? 1.0 : 0.0;
                } else if (valueNode.isNumber()) {
                    value = valueNode.asDouble();
                } else if (valueNode.isBoolean()) {
                    value = valueNode.asBoolean() ? 1.0 : 0.0;
                } else {
                    //숫자화할 수 없는 항목은 조용한 0.0 오염 대신 건너뛴다
                    log.debug("[skip] 비숫자 측정항목: {}:{} = {}", deviceEui, measurement, valueNode);
                    continue;
                }

                //SensorReading 조립
                SensorReading sensorReading = new SensorReading(message.getTraceId(), location, point, deviceEui,
                        measurement,value, measuredAt, receivedAt, deviceName, fCnt);
                //수신 기록
                lastSeenRegistry.update(deviceEui, measurement, receivedAt);

                send("out", Message.of(message.getTraceId(), Map.of(
                        "sensorReading", sensorReading,
                        "_timeSubstituted", timeSubstituted)));
            }
        } catch (JsonProcessingException | DateTimeParseException e) {
            log.warn("[무효] payload 파싱 실패: raw={}", raw, e);
            QualityEvent qualityEvent = QualityEvent.invalid(message.getTraceId(), "payload 파싱 실패: " + e.getMessage());
            send("invalid", Message.of(message.getTraceId(), Map.of("qualityEvent", qualityEvent)));
        }
    }
}