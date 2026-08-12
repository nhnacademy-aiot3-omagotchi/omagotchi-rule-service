package site.omagotchi.ruleservice.distributed.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

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
    @DisplayName("ApplicationReadyEvent 시점에 모든 Activatable 노드를 즉시 activate한다")
    void activatesAllActivatableNodesOnReady() {
        Activatable node1 = mock(Activatable.class);
        Activatable node2 = mock(Activatable.class);

        when(this.flowManager.getActivatableNodes()).thenReturn(List.of(node1, node2));

        this.singleEngineMode.activateAll();

        verify(node1).activate();
        verify(node2).activate();
    }

    @Test
    @DisplayName("단일 엔진 모드에서는 항상 스스로를 ACTIVE로 취급한다")
    void isSelfActiveAlwaysTrue() {
        assertThat(this.singleEngineMode.isSelfActive()).isTrue();
    }

    @Test
    @DisplayName("sync* 메서드는 전달할 파트너가 없으므로 아무 동작 없이 예외 없이 끝난다")
    void syncMethodsAreNoPo() {
        assertThatCode(() -> {
            this.singleEngineMode.syncStart("flow-1");
            this.singleEngineMode.syncStop("flow-1");
            this.singleEngineMode.syncRestart("flow-1");
            this.singleEngineMode.syncReconfigure("flow-1", "node-1", Map.of("key", "value"));
        }).doesNotThrowAnyException();
    }
}
