package site.omagotchi.ruleservice.distributed.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.distributed.domain.TopologyHealth;
import site.omagotchi.ruleservice.distributed.presentation.response.FlowTopologyResponse;
import site.omagotchi.ruleservice.flow.application.FlowErrorCode;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.global.exception.BusinessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopologyServiceTest {

    private FlowManager flowManager;
    private EngineDirectoryPort engineDirectoryPort;
    private TopologyService topologyService;

    @BeforeEach
    void setUp() {
        this.flowManager = mock(FlowManager.class);
        this.engineDirectoryPort = mock(EngineDirectoryPort.class);
        EngineProperties engineProperties = new EngineProperties("engine-a", 1, 1); // 기대 피어 1개(A/B 이중화 구성)
        this.topologyService = new TopologyService(this.flowManager, this.engineDirectoryPort, engineProperties);
    }

    @Test
    @DisplayName("존재하지 않는 flowId면 FLOW_NOT_FOUND BusinessException 던진다")
    void throwsWhenFlowNotFound() {
        when(this.flowManager.getStatus("unknown-flow-id"))
                .thenThrow(new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));

        assertThatThrownBy(() -> this.topologyService.getTopology("unknown-flow-id"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(FlowErrorCode.FLOW_NOT_FOUND);
    }

    @Test
    @DisplayName("피어가 전부 ONLINE이면 HEALTHY로 판정한다")
    void healthyWhenAllPeersOnline() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", PresenceStatus.ONLINE)
        ));

        FlowTopologyResponse response = this.topologyService.getTopology("flow-1");

        assertThat(response.flowId()).isEqualTo("flow-1");
        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.HEALTHY);
    }

    @Test
    @DisplayName("피어가 하나도 없으면 이중화 미확보이므로 DEGRADED로 판정")
    void degradedWhenNoPeersButPeerExpected() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());

        FlowTopologyResponse response = this.topologyService.getTopology("flow-1");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.DEGRADED);
        assertThat(response.reason()).contains("이중화 미확보");
    }

    @Test
    @DisplayName("기대 피어 수가 0이면(의도적 단일 엔진 운영) 피어가 없어도 HEALTHY로 판정한다")
    void healthyWhenNoPeersAndNoneExpected() {
        TopologyService singleEngineModeTopology = new TopologyService(
                this.flowManager, this.engineDirectoryPort, new EngineProperties("engine-a", 1, 0)
        );

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());

        FlowTopologyResponse response = singleEngineModeTopology.getTopology("flow-1");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.HEALTHY);
    }

    @Test
    @DisplayName("피어 중 하나라도 OFFLINE이면 DEGRADED + 사유에 해당 engineId가 포함된다")
    void degradedWhenAnyPeerOffline() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", PresenceStatus.OFFLINE)
        ));

        FlowTopologyResponse response = this.topologyService.getTopology("flow-id");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.DEGRADED);
        assertThat(response.reason()).contains("engine-b");
    }

    @Test
    @DisplayName("피어가 여럿이고 일부만 ONLINE이면, OFFLINE인 피어만 사유에 포함된다")
    void degradedReasonListsOnlyOfflinePeers() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", PresenceStatus.ONLINE),
                peer("engine-c", PresenceStatus.OFFLINE)
        ));

        FlowTopologyResponse response = this.topologyService.getTopology("flow-1");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.DEGRADED);
        assertThat(response.reason()).contains("engine-c").doesNotContain("engine-b");
    }

    @Test
    @DisplayName("피어가 AUTH_FAILED면 DEGRADED + 사유에 인증 실패 메시지가 포함된다")
    void degradedWhenPeerAuthFailed() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", PresenceStatus.AUTH_FAILED)
        ));

        FlowTopologyResponse response = this.topologyService.getTopology("flow-1");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.DEGRADED);
        assertThat(response.reason()).contains("engine-b").contains("인증 실패");
    }

    @Test
    @DisplayName("OFFLINE 피어와 AUTH_FAILED 피어가 섞여 있으면 사유에 둘 다 포함된다")
    void degradedReasonCombinesOfflineAndAuthFailedPeers() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", PresenceStatus.OFFLINE),
                peer("engine-c", PresenceStatus.AUTH_FAILED)
        ));

        FlowTopologyResponse response = this.topologyService.getTopology("flow-1");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.DEGRADED);
        assertThat(response.reason()).contains("engine-b").contains("engine-c").contains("인증 실패");
    }

    @Test
    @DisplayName("피어가 전부 ONLINE이면 AUTH_FAILED 이력이 없으므로 HEALTHY로 판정한다")
    void healthyWhenNoOfflineOrAuthFailedPeers() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", PresenceStatus.ONLINE),
                peer("engine-c", PresenceStatus.ONLINE)
        ));

        FlowTopologyResponse response = this.topologyService.getTopology("flow-1");

        assertThat(response.topologyHealth()).isEqualTo(TopologyHealth.HEALTHY);
    }

    private static EngineInfo peer(String engineId, PresenceStatus presenceStatus) {
        return new EngineInfo(
                engineId,
                "localhost",
                8080,
                1,
                0L,
                presenceStatus,
                null
        );
    }
}
