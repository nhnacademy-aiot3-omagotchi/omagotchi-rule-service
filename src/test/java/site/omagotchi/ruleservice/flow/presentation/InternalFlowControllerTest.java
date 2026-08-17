package site.omagotchi.ruleservice.flow.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.omagotchi.ruleservice.flow.application.FlowConfigService;
import site.omagotchi.ruleservice.flow.application.FlowManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InternalFlowControllerTest {

    private static final String FLOW_ID = "flow-1";
    private static final String NODE_ID = "node-1";

    @Mock
    private FlowManager flowManager;

    @Mock
    private FlowConfigService flowConfigService;

    private InternalFlowController internalFlowController;

    @BeforeEach
    void setUp() {
        this.internalFlowController = new InternalFlowController(flowManager, flowConfigService);
    }

    @Test
    @DisplayName("POST /api/v1/internal/flows/{flow-id}/start는 startFromPeer만 호출하고 start는 호출하지 않는다")
    void startFromPeerCallsLocalApplyOnlyTest() {
        ResponseEntity<Void> response = this.internalFlowController.startFromPeer(FLOW_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(this.flowManager).startFromPeer(FLOW_ID);
        verify(this.flowManager, never()).start(FLOW_ID); // 재전달 없어야 함 (무한루프 방지)
    }

    @Test
    @DisplayName("POST /api/v1/internal/flows/{flow-id}/stop은 stopFromPeer만 호출하고 stop은 호출하지 않는다")
    void stopFromPeerCallsLocalApplyOnlyTest() {
        ResponseEntity<Void> response = this.internalFlowController.stopFromPeer(FLOW_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(this.flowManager).stopFromPeer(FLOW_ID);
        verify(this.flowManager, never()).stop(FLOW_ID);
    }

    @Test
    @DisplayName("POST /api/v1/internal/flows/{flow-id}/restart는 restartFromPeer만 호출하고 restart는 호출하지 않는다")
    void restartFromPeerCallsLocalApplyOnlyTest() {
        ResponseEntity<Void> response = this.internalFlowController.restartFromPeer(FLOW_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(this.flowManager).restartFromPeer(FLOW_ID);
        verify(this.flowManager, never()).restart(FLOW_ID);
    }

    @Test
    @DisplayName("PATCH .../config는 reconfigureFromPeer만 호출하고 reconfigure는 호출하지 않는다")
    void reconfigureFromPeerCallsLocalApplyOnlyTest() {
        Map<String, Object> config = Map.of("threshold", 30);

        ResponseEntity<Void> response = this.internalFlowController.reconfigureFromPeer(FLOW_ID, NODE_ID, config);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(this.flowConfigService).reconfigureFromPeer(FLOW_ID, NODE_ID, config);
        verify(this.flowConfigService, never()).reconfigure(FLOW_ID, NODE_ID, config);
    }
}
