package site.omagotchi.ruleservice.rule.domain;

import site.omagotchi.ruleservice.quality.domain.QualityEvent;
import site.omagotchi.ruleservice.quality.domain.RecordingConnection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;
import site.omagotchi.ruleservice.rule.domain.Operator;
import site.omagotchi.ruleservice.rule.domain.ThresholdRule;
import site.omagotchi.ruleservice.rule.domain.RuleCache;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThresholdRuleNodeTest {

    @Mock
    private RuleCache ruleCache;

    private ThresholdRuleNode node;
    private RecordingConnection out;
    private RecordingConnection ruleHit;

    @BeforeEach
    void setUp(){
        node = new ThresholdRuleNode("threshold",ruleCache);
        out = new RecordingConnection();
        ruleHit = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("ruleHit").connect(ruleHit);
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
    @DisplayName("룰에 적중하지 않으면 표시 없이 통과한다")
    void noRuleHitPassesTest(){
        when(ruleCache.evaluate("eui-1","co2",500.0)).thenReturn(Optional.empty());

        node.process(message("co2",500.0));

        assertThat(out.messages()).hasSize(1);
        assertThat(ruleHit.messages()).isEmpty();
        assertThat((Boolean) out.messages().get(0).get("_ruleHit")).isNull();
    }

    @Test
    @DisplayName("룰에 적중하면 표시를 달고 통과하며 RULE_HIT 이벤트를 발행한다")
    void ruleHitMarksAndReportsTest(){
        ThresholdRule rule = new ThresholdRule(1L,"eui-1","co2", Operator.GT,1000.0,1L,0L);

        when(ruleCache.evaluate("eui-1","co2",1500.0)).thenReturn(Optional.of(rule));

        node.process(message("co2",1500.0));

        assertThat(out.messages()).hasSize(1);
        assertThat((Boolean) out.messages().get(0).get("_ruleHit")).isTrue();

        QualityEvent qualityEvent = ruleHit.messages().get(0).get("qualityEvent");
        assertThat(ruleHit.messages()).hasSize(1);
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.RULE_HIT);
        assertThat(qualityEvent.traceId()).isEqualTo("trace-1");
        assertThat(qualityEvent.value()).isEqualTo(1500.0);
        assertThat(qualityEvent.detail()).isEqualTo("룰 적중: co2 GT 1000.0");
    }
}
