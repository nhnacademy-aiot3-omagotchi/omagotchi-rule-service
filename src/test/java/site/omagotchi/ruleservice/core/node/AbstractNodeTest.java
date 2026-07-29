package site.omagotchi.ruleservice.core.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import site.omagotchi.ruleservice.core.connection.LocalConnection;
import site.omagotchi.ruleservice.core.message.Message;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AbstractNodeTest {

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
    @DisplayName("onProcess 실행 중에는 MDC의 traceId가 메시지의 traceId와 같고, 종료 후에는 제거된다")
    void setsAndClearsMdcTraceIdAroundProcessing() {
        AtomicReference<String> mdcDuringProcessing = new AtomicReference<>();
        RecordingNode node = new RecordingNode("node-1", msg -> {
            mdcDuringProcessing.set(MDC.get("traceId"));
        });
        Message msg = Message.of(Map.of("value", 1));

        node.process(msg);

        assertThat(mdcDuringProcessing.get()).isEqualTo(msg.getTraceId());
        assertThat(MDC.get("traceId")).isNull();
    }
}