package site.omagotchi.ruleservice.flow.domain.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import site.omagotchi.ruleservice.flow.domain.connection.LocalConnection;
import site.omagotchi.ruleservice.flow.domain.Message;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class AbstractNodeTest {
    private static final String PIPELINE_CORRELATION_ID = "pipeline.correlation.id";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("생성 시 지정한 id를 그대로 반환한다")
    void ReturnsIdSetAtConstruction() {
        RecordingNode node = new RecordingNode("node-1");

        assertThat(node.getId()).isEqualTo("node-1");
    }

    @Test
    @DisplayName("포트를 등록하면 이름으로 조회 가능하고, 미등록 포트 조회는 null이다")
    void registersAndLooksUpPorts() {
        RecordingNode node = new RecordingNode("node-1");
        node.openInputPort("in");
        node.openOutputPort("out");

        assertThat(node.getInputPort("in")).isNotNull();
        assertThat(node.getOutputPort("out")).isNotNull();
        assertThat(node.getInputPort("없는포트")).isNull();
        assertThat(node.getOutputPort("없는포트")).isNull();
    }

    @Test
    @DisplayName("process() 호출 시 하위 구현의 onProcess()가 실행된다")
    void processDelegatesToOnProcess() {
        RecordingNode node = new RecordingNode("node-1");
        Message msg = Message.of(Map.of("value", 1));

        node.process(msg);

        assertThat(node.getLastProcessed()).isEqualTo(msg);
    }

    @Test
    @DisplayName("OutputPort에 Connection을 연결하면 send()로 상대측이 수신한다")
    void sendDeliversMsgThroughConnectedOutputPort() throws InterruptedException {
        RecordingNode node = new RecordingNode("node-1");
        node.openOutputPort("out");
        LocalConnection conn = new LocalConnection();
        node.getOutputPort("out").connect(conn);

        Message msg = Message.of(Map.of("value", 1));
        node.sendTo("out", msg);

        assertThat(conn.poll()).isEqualTo(msg);
    }

    @Test
    @DisplayName("onProcess가 예외를 던져도 process() 호출부로 전파되지 않는다")
    void isolatesExceptionThrownByOnProcess() {
        RecordingNode node = new RecordingNode("node-1", msg -> {
            throw new RuntimeException("의도적 실패");
        });

        Message msg = Message.of(Map.of("value", 1));

        assertThatCode(() -> node.process(msg)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("처리 중에는 파이프라인 상관관계 ID를 제공하고 종료 후 이전 값을 복원한다")
    void restoresPreviousPipelineCorrelationIdAfterProcessing() {
        MDC.put(PIPELINE_CORRELATION_ID, "outer-pipeline");
        AtomicReference<String> mdcDuringProcessing = new AtomicReference<>();
        RecordingNode node = new RecordingNode("node-1", msg -> {
            mdcDuringProcessing.set(MDC.get(PIPELINE_CORRELATION_ID));
        });
        Message msg = Message.of(Map.of("value", 1));

        node.process(msg);

        assertThat(mdcDuringProcessing.get()).isEqualTo(msg.getTraceId());
        assertThat(MDC.get(PIPELINE_CORRELATION_ID)).isEqualTo("outer-pipeline");
    }

    @Test
    @DisplayName("id가 null이거나 비어있으면 생성 시 IllegalArgumentException을 던진다")
    void constructorWithBlankIdThrowsException() {
        assertThatThrownBy(() -> new RecordingNode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecordingNode("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 이름의 InputPort/OutputPort를 중복 등록하면 IllegalStateException을 던진다")
    void duplicatePortNameThrowsException() {
        RecordingNode node = new RecordingNode("node-1");
        node.openInputPort("in");
        node.openOutputPort("out");

        assertThatThrownBy(() -> node.openInputPort("in")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> node.openOutputPort("out")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("포트 이름이 null이거나 비어있으면 IllegalArgumentException을 던진다")
    void blankPortNameThrowsException() {
        RecordingNode node = new RecordingNode("node-1");

        assertThatThrownBy(() -> node.openInputPort(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.openInputPort("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.openOutputPort(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.openOutputPort("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("process()에 null 메시지를 전달하면 onProcess를 호출하지 않고 조용히 반환한다")
    void processWithNullMessageSkipsOnProcess() {
        RecordingNode node = new RecordingNode("node-1");

        assertThatCode(() -> node.process(null)).doesNotThrowAnyException();
        assertThat(node.getLastProcessed()).isNull();
    }

    @Test
    @DisplayName("send()에 null 메시지를 전달하면 예외 없이 아무 일도 일어나지 않는다")
    void sendWithNullMessageDoesNothing() {
        RecordingNode node = new RecordingNode("node-1");
        node.openOutputPort("out");

        assertThatCode(() -> node.sendTo("out", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("존재하지 않는 포트로 send()해도 예외 없이 무시된다")
    void sendToUnknownPortDoesNothing() {
        RecordingNode node = new RecordingNode("node-1");
        Message msg = Message.of(Map.of("value", 1));

        assertThatCode(() -> node.sendTo("없는포트", msg)).doesNotThrowAnyException();
    }
}
