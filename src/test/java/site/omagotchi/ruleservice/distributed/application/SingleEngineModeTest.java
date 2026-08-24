package site.omagotchi.ruleservice.distributed.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.flow.application.FlowManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SingleEngineModeTest {

    @Mock
    private FlowManager flowManager;

    private SingleEngineMode singleEngineMode;

    @BeforeEach
    void setUp() {
        this.singleEngineMode = new SingleEngineMode(flowManager);
    }

    @Test
    @DisplayName("ApplicationReadyEvent 시점에 FlowManager에 활성화 상태를 위임한다")
    void activatesAllActivatableNodesOnReady() {
        this.singleEngineMode.activateAll();

        verify(this.flowManager).applyActivationState(true);
    }

    @Test
    @DisplayName("단일 엔진 모드에서는 항상 스스로를 ACTIVE로 취급한다")
    void isSelfActiveAlwaysTrue() {
        assertThat(this.singleEngineMode.isSelfActive()).isTrue();
    }

    @Test
    @DisplayName("sync* 메서드는 전달할 파트너가 없으므로 아무 동작 없이 예외 없이 끝난다")
    void syncMethodsAreNoOp() {
        assertThatCode(() -> {
            this.singleEngineMode.syncStart("flow-1");
            this.singleEngineMode.syncStop("flow-1");
            this.singleEngineMode.syncRestart("flow-1");
            this.singleEngineMode.syncReconfigure("flow-1", "node-1", Map.of("key", "value"));
        }).doesNotThrowAnyException();
    }
}
