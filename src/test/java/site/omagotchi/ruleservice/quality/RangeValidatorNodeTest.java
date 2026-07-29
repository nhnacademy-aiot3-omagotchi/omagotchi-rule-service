package site.omagotchi.ruleservice.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.core.message.Message;
import site.omagotchi.ruleservice.inbound.SensorReading;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RangeValidatorNodeTest {

    private RangeValidatorNode node;
    private RecordingConnection out;
    private RecordingConnection anomaly;

    @BeforeEach
    void setUp(){
        PhysicalRangeTable table = new PhysicalRangeTable(
                Map.of(
                        "temperature", new PhysicalRange(-20.0, 60.0),
                        "illumination", new PhysicalRange(0.0, null)
                )
        );
        node = new RangeValidatorNode("range",table);
        out = new RecordingConnection();
        anomaly = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("anomaly").connect(anomaly);
    }

    private Message message(String measurement, double value){
        Instant now = Instant.now();
        SensorReading sensorReading = new SensorReading(
                "trace-1", "실습실", "전방", "eui-1", measurement,
                value, now, now, "sensor-1"
        );
        return Message.of("trace-1", Map.of("sensorReading", sensorReading));
    }

    @Test
    @DisplayName("범위 안 값은 표시 없이 통과하고 이상치 신고가 없다")
    void inRangePassesTest(){
        node.process(message("temperature",25.0));

        assertEquals(1, out.messages().size());
        assertEquals(0, anomaly.messages().size());
        assertNull(out.messages().get(0).get("_anomaly"));
    }

    @Test
    @DisplayName("하한과 상한 경계값은 정상으로 판정한다")
    void boundaryValuesAreNormalTest(){
        node.process(message("temperature", -20.0));
        node.process(message("temperature", 60.0));

        assertEquals(2, out.messages().size());
        assertEquals(0, anomaly.messages().size());
    }

    @Test
    @DisplayName("범위 밖 값은 이상치 표시를 달고 통과하면서 신고도 발행한다")
    void outOfRangeMarksAndReportsTest(){
        node.process(message("temperature", 100.0));

        assertEquals(1, out.messages().size());
        assertTrue((Boolean) out.messages().get(0).get("_anomaly"));

        QualityEvent qualityEvent = anomaly.messages().get(0).get("qualityEvent");
        assertEquals(1, anomaly.messages().size());
        assertEquals(QualityEvent.Type.ANOMALY,qualityEvent.type());
        assertEquals("trace-1",qualityEvent.traceId());
        assertEquals("temperature",qualityEvent.measurement());
        assertEquals(100.0,qualityEvent.value());
        assertEquals("범위 초과: temperature 100.0",qualityEvent.detail());
    }

    @Test
    @DisplayName("하한 미만 값도 이상치로 판정한다")
    void belowMinIsAnomalyTest(){
        node.process(message("temperature", -30.0));

        assertEquals(1,out.messages().size());
        assertEquals(1,anomaly.messages().size());
    }

    @Test
    @DisplayName("상한이 없는 측정항목은 아무리 큰 값이어도 통과한다")
    void noUpperBoundPassesTest(){
        node.process(message("illumination", 50000.0));

        assertEquals(1,out.messages().size());
        assertEquals(0,anomaly.messages().size());
    }
    @Test
    @DisplayName("범위표에 없는 측정항목은 검사 없이 통과한다")
    void unknownMeasurementPassesTest(){
        node.process(message("unknown", 999.0));

        assertEquals(1,out.messages().size());
        assertEquals(0,anomaly.messages().size());
    }
}
