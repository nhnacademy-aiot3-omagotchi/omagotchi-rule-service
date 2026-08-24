package site.omagotchi.ruleservice.distributed.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.omagotchi.ruleservice.distributed.application.TopologyService;
import site.omagotchi.ruleservice.distributed.domain.TopologyHealth;
import site.omagotchi.ruleservice.distributed.presentation.response.FlowTopologyResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowTopologyControllerTest {

    private static final String FLOW_ID = "flow-1";

    @Mock
    private TopologyService topologyService;

    private FlowTopologyController flowTopologyController;

    @BeforeEach
    void setUp() {
        this.flowTopologyController = new FlowTopologyController(topologyService);
    }

    @Test
    @DisplayName("GET /api/v1/flows/{flow-id}/topology는 TopologyService 결과를 그대로 200으로 반환한다")
    void topologyReturnsServiceResultAsOk() {
        FlowTopologyResponse response = new FlowTopologyResponse(
                FLOW_ID,
                TopologyHealth.HEALTHY,
                "모든 엔진 정상"
        );
        when(this.topologyService.getTopology(FLOW_ID)).thenReturn(response);

        ResponseEntity<FlowTopologyResponse> result = this.flowTopologyController.topology(FLOW_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
        verify(this.topologyService).getTopology(FLOW_ID);
    }

    @Test
    @DisplayName("DEGRADED 결과도 그대로 200으로 반환한다 (상태 자체를 HTTP 레벨에서 가공하지 않음)")
    void topologyPassesThroughDegradedStatusAsOk() {
        FlowTopologyResponse response = new FlowTopologyResponse(
                FLOW_ID,
                TopologyHealth.DEGRADED,
                "engine-b OFFLINE - 단독 운전 중"
        );
        when(this.topologyService.getTopology(FLOW_ID)).thenReturn(response);

        ResponseEntity<FlowTopologyResponse> result = this.flowTopologyController.topology(FLOW_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().topologyHealth()).isEqualTo(TopologyHealth.DEGRADED);
    }
}
