package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.quality.domain.QualityEvent;
import site.omagotchi.ruleservice.quality.domain.LastSeenRegistry;
import site.omagotchi.ruleservice.quality.infrastructure.QualityProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class MissingDetectorNodeTest {

    private LastSeenRegistry registry;
    private MissingDetectorNode node;
    private RecordingConnection missing;

    @BeforeEach
    void setUp(){
        registry = new LastSeenRegistry();

        QualityProperties properties = new QualityProperties(
                Map.of(), List.of(new QualityProperties.SensorId("eui-1", "temperature", 60))
        );

        node = new MissingDetectorNode("missing", registry, properties);
        missing = new RecordingConnection();
        node.getOutputPort("missing").connect(missing);

        node.initialize();
    }

    @AfterEach
    void tearDown() {
        node.shutdown();
    }

    @Test
    @DisplayName("기동 직후 보류 기간에는 결측으로 판정하지 않는다")
    void noFalsePositiveRightAfterStartTest() {
        node.check();

        assertThat(missing.messages()).isEmpty();
    }

    @Test
    @DisplayName("임계를 넘게 소식이 없으면 결측 시작을 발행한다")
    void missingStartTest() {
        registry.update("eui-1", "temperature", Instant.now().minusSeconds(300));

        node.check();

        assertThat(missing.messages()).hasSize(1);
        QualityEvent qualityEvent = missing.messages().get(0).get("qualityEvent");
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.MISSING);
        assertThat(qualityEvent.deviceEui()).isEqualTo("eui-1");
        assertThat(qualityEvent.detail()).isEqualTo("결측 시작");
    }

    @Test
    @DisplayName("결측 상태가 이어져도 신고를 반복하지 않는다")
    void noRepeatedAlertTest() {
        registry.update("eui-1", "temperature", Instant.now().minusSeconds(300));

        node.check();
        node.check();
        node.check();

        assertThat(missing.messages()).hasSize(1);
    }

    @Test
    @DisplayName("다시 수신되면 결측 종료를 발행한다")
    void missingEndTest() {
        registry.update("eui-1", "temperature", Instant.now().minusSeconds(300));
        node.check();

        registry.update("eui-1", "temperature", Instant.now());
        node.check();

        assertThat(missing.messages()).hasSize(2);
        QualityEvent qualityEvent = missing.messages().get(1).get("qualityEvent");
        assertThat(qualityEvent.detail()).isEqualTo("결측 종료");
    }

    @Test
    @DisplayName("센서마다 설정된 주기에 따라 임계가 다르게 적용된다")
    void perSensorThresholdTest() {
        QualityProperties properties = new QualityProperties(Map.of(), List.of(
                new QualityProperties.SensorId("fast", "temperature", 5),
                new QualityProperties.SensorId("slow", "temperature", 900)
        ));
        MissingDetectorNode node2 = new MissingDetectorNode("m2", registry, properties);
        RecordingConnection missing2 = new RecordingConnection();
        node2.getOutputPort("missing").connect(missing2);
        node2.initialize();

        Instant twentySecondsAgo = Instant.now().minusSeconds(20);
        registry.update("fast", "temperature", twentySecondsAgo);
        registry.update("slow", "temperature", twentySecondsAgo);

        node2.check();

        assertThat(missing2.messages()).hasSize(1);
        QualityEvent event = missing2.messages().get(0).get("qualityEvent");
        assertThat(event.deviceEui()).isEqualTo("fast");

        node2.shutdown();
    }
}
