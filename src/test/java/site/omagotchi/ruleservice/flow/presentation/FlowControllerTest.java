package site.omagotchi.ruleservice.flow.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.omagotchi.ruleservice.core.engine.FlowManager;
import site.omagotchi.ruleservice.core.engine.FlowState;
import site.omagotchi.ruleservice.core.engine.dto.FlowSummary;
import site.omagotchi.ruleservice.flow.application.FlowConfigService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowControllerTest {

    private static final String FLOW_ID = "flow-1";

    @Mock
    private FlowManager flowManager;

    @Mock
    private FlowConfigService flowConfigService;

    private FlowController flowController;

    @BeforeEach
    void setUp() {
        flowController = new FlowController(flowManager, flowConfigService);
    }

    @Test
    @DisplayName("GET /flows는 FlowManager.listSummaries() 결과를 200으로 리턴한다")
    void getFlowListReturnsSummariesTest() {
        List<FlowSummary> summaries = List.of(new FlowSummary(FLOW_ID, FlowState.RUNNING, List.of("nodeA")));
        when(flowManager.listSummaries()).thenReturn(summaries);

        ResponseEntity<List<FlowSummary>> response = flowController.getFlowList();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(summaries);
    }

    @Test
    @DisplayName("GET /flows/{flowId}는 FlowManager.getSummary() 결과를 200으로 리턴한다")
    void getFlowReturnsSummaryTest() {
        FlowSummary flowSummary = new FlowSummary(FLOW_ID, FlowState.RUNNING, List.of("nodeA"));
        when(flowManager.getSummary(FLOW_ID)).thenReturn(flowSummary);

        ResponseEntity<FlowSummary> response = flowController.getFlow(FLOW_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(flowSummary);
    }

    @Test
    @DisplayName("POST /flows/{flowId}/start는 FlowManager.start()를 호출하고 최신 상태를 반환한다")
    void startCallsFlowManagerAndReturnSummaryTest() {
        FlowSummary summary = new FlowSummary(FLOW_ID, FlowState.RUNNING, List.of("nodeA"));
        when(flowManager.getSummary(FLOW_ID)).thenReturn(summary);

        ResponseEntity<FlowSummary> response = flowController.start(FLOW_ID);

        verify(flowManager).start(FLOW_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(summary);
    }

    @Test
    @DisplayName("POST /flows/{flowId}/stop은 FlowManager.stop()을 호출하고 최신 상태를 반환한다")
    void stopCallsFlowManagerAndReturnsSummaryTest() {
        FlowSummary summary = new FlowSummary(FLOW_ID, FlowState.STOPPED, List.of("nodeA"));
        when(flowManager.getSummary(FLOW_ID)).thenReturn(summary);

        ResponseEntity<FlowSummary> response = flowController.stop(FLOW_ID);

        verify(flowManager).stop(FLOW_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(summary);
    }

    @Test
    @DisplayName("POST /flows/{flowId}/restart는 FlowManager.restart()를 호출하고 최신 상태를 반환한다")
    void restartCallsFlowManagerAndReturnsSummaryTest() {
        FlowSummary summary = new FlowSummary(FLOW_ID, FlowState.RUNNING, List.of("nodeA"));
        when(flowManager.getSummary(FLOW_ID)).thenReturn(summary);

        ResponseEntity<FlowSummary> response = flowController.restart(FLOW_ID);

        verify(flowManager).restart(FLOW_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(summary);
    }

    @Test
    @DisplayName("PATCH /flows/{flowId}/nodes/{nodeId}/config는 FlowConfigService.reconfigure()를 호출하고 204를 리턴한다.")
    void reconfigureCallsFlowConfigServiceAndReturnsNoContentTest() {
        Map<String, Object> config = Map.of("threshold", 200);

        ResponseEntity<Void> response = flowController.reconfigure(FLOW_ID, "node-a", config);

        verify(flowConfigService).reconfigure(FLOW_ID, "node-a", config);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}