package site.omagotchi.ruleservice.flow.domain.node;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.port.InputPort;
import site.omagotchi.ruleservice.flow.domain.port.OutputPort;
import site.omagotchi.ruleservice.flow.domain.port.DefaultInputPort;
import site.omagotchi.ruleservice.flow.domain.port.DefaultOutputPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
public abstract class AbstractNode implements Node {

    private static final String PIPELINE_CORRELATION_ID = "pipeline.correlation.id";

    private final String id;
    private final Map<String, InputPort> inputPorts = new LinkedHashMap<>();
    private final Map<String, OutputPort> outputPorts = new LinkedHashMap<>();

    protected AbstractNode(String id) {

        if (Objects.isNull(id) || id.isBlank()) {
            throw new IllegalArgumentException("노드 ID가 null이거나 비어있습니다.");
        }

        this.id = id;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public final void process(Message message) {

        if (Objects.isNull(message)) {
            log.warn("[{}] message가 null입니다. 처리를 건너뜁니다.", id);
            return;
        }

        String previousCorrelationId = MDC.get(PIPELINE_CORRELATION_ID);
        if (message.getTraceId() == null) {
            MDC.remove(PIPELINE_CORRELATION_ID);
        } else {
            MDC.put(PIPELINE_CORRELATION_ID, message.getTraceId());
        }

        try {
            log.debug("[{}] 메시지 처리 시작: {}", this.id, message);
            onProcess(message);
            log.debug("[{}] 메시지 처리 완료", this.id);
        } catch (Exception e) {
            log.error("[{}] 메시지 처리 중 예외 발생 (메시지는 격리되고 노드는 계속 동작)", this.id, e);
        } finally {
            if (previousCorrelationId == null) {
                MDC.remove(PIPELINE_CORRELATION_ID);
            } else {
                MDC.put(PIPELINE_CORRELATION_ID, previousCorrelationId);
            }
        }
    }

    protected abstract void onProcess(Message message);

    protected InputPort addInputPort(String name) {

        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        if (this.inputPorts.containsKey(name)) {
            throw new IllegalStateException("이미 존재하는 포트 이름입니다: " + name);
        }

        InputPort inputPort = new DefaultInputPort(name, this);
        this.inputPorts.put(name, inputPort);

        log.debug("[{}] InputPort 등록: {}", this.id, name);
        return inputPort;
    }

    protected OutputPort addOutputPort(String name) {

        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        if (this.outputPorts.containsKey(name)) {
            throw new IllegalStateException("이미 존재하는 포트 이름입니다: " + name);
        }

        OutputPort outputPort = new DefaultOutputPort(name);
        this.outputPorts.put(name, outputPort);

        log.debug("[{}] OutputPort 등록: {}", this.id, name);
        return outputPort;
    }

    public InputPort getInputPort(String name) {
        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        return this.inputPorts.get(name);
    }

    public OutputPort getOutputPort(String name) {

        if (Objects.isNull(name) || name.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        return this.outputPorts.get(name);
    }

    protected void send(String portName, Message message) {

        if (Objects.isNull(portName) || portName.isBlank()) {
            throw new IllegalArgumentException("포트 이름이 null이거나 비어있습니다.");
        }

        if (Objects.isNull(message)) {
            log.warn("[{}] message가 null입니다. null 메시지는 전송하지 않습니다 - port: {}", id, portName);
            return;
        }

        OutputPort outputPort = outputPorts.get(portName);

        if (Objects.isNull(outputPort)) {
            log.warn("[{}] 존재하지 않는 OutputPort로 send 시도: {}", id, portName);
            return;
        }

        outputPort.send(message);
    }

    @Override
    public void initialize() {
        // 기본 빈 구현 (자원이 필요한 노드가 오버라이드)
    }

    @Override
    public void shutdown() {
        // 기본 빈 구현 (자원이 필요한 노드가 오버라이드)
    }
}