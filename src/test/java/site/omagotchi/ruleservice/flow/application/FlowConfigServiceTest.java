package site.omagotchi.ruleservice.flow.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.application.FlowErrorCode;
import site.omagotchi.ruleservice.flow.application.port.PeerFlowSyncPort;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Reconfigurable;
import site.omagotchi.ruleservice.global.exception.BusinessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowConfigServiceTest {

    private static final String FLOW_ID = "flow-1";
    private static final String NODE_ID = "node-a";

    @Mock
    private FlowManager flowManager;

    @Mock
    private PeerFlowSyncPort peerFlowSyncPort;

    private FlowConfigService flowConfigService;

    @BeforeEach
    void setUp() {
        flowConfigService = new FlowConfigService(flowManager, peerFlowSyncPort);
    }

    @Test
    @DisplayName("존재하지 않는 노드면 FlowManager가 던진 BusinessException이 그대로 전파된다")
    void nodeNotFoundPropagatesTest() {

        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenThrow(new BusinessException(FlowErrorCode.NODE_NOT_FOUND, "flowId = %s, nodeId = %s".formatted(FLOW_ID, NODE_ID)));

        assertThatThrownBy(() -> flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 10)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Reconfigurable을 구현하지 않은 노드면 BusinessException을 던진다")
    void nodeReconfigurableNodeThrowsTest() {

        AbstractNode plainNode = mock(AbstractNode.class);
        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenReturn(plainNode);

        assertThatThrownBy(() -> flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 10)))
                .isInstanceOf(BusinessException.class);

        verify(flowManager, never()).getNodeConfig(any(), any());
    }

    @Test
    @DisplayName("유효한 config는 노드에 그대로 반영된다 (config 무중단)")
    void appliesNewConfigTest() {

        FakeReconfigurableNode node = new FakeReconfigurableNode(NODE_ID, 100);
        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenReturn(node);
        when(flowManager.getNodeConfig(FLOW_ID, NODE_ID)).thenReturn(Map.of("threshold", 100));

        flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 200));

        assertThat(node.getThreshold()).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 노드에 대한 첫 PATCH만 정적 config를 스냅샷으로 조회하고, 이후에는 재조회하지 않는다")
    void staticConfigFetchedOnlyOnceTest() {

        FakeReconfigurableNode node = new FakeReconfigurableNode(NODE_ID, 100);
        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenReturn(node);
        when(flowManager.getNodeConfig(FLOW_ID, NODE_ID)).thenReturn(Map.of("threshold", 100));

        flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 200));
        flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 300));

        verify(flowManager, times(1)).getNodeConfig(FLOW_ID, NODE_ID);
    }

    @Test
    @DisplayName("reconfigure 실패 시 BusinessException으로 감싸서 던지고, 노드는 이전 값으로 원복된다.")
    void restoresPreviousConfigOnFailureTest() {

        FakeReconfigurableNode node = new FakeReconfigurableNode(NODE_ID, 100);
        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenReturn(node);
        when(flowManager.getNodeConfig(FLOW_ID, NODE_ID)).thenReturn(Map.of("threshold", 100));

        assertThatThrownBy(() ->
                flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", -1))) // 0 이상의 정수여야 함 (FakeReconfigurableNode의 정책)
                .isInstanceOf(BusinessException.class);

        assertThat(node.getThreshold()).isEqualTo(100);
    }

    @Test
    @DisplayName("성공 이후의 실패는 정적 config가 아니라 직전 성공값으로 원복된다")
    void restoresToLastSuccessfulConfigNotStaticOneTest() {

        FakeReconfigurableNode node = new FakeReconfigurableNode(NODE_ID, 100);
        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenReturn(node);
        when(flowManager.getNodeConfig(FLOW_ID, NODE_ID)).thenReturn(Map.of("threshold", 100));

        flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 200)); // 성공 (200으로 바뀜)

        assertThatThrownBy(() ->
                flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", -1))) // 실패
                .isInstanceOf(BusinessException.class);

        // 정적 원본(100)이 아니라 직전 성공값(200)으로 돌아가야 함
        assertThat(node.getThreshold()).isEqualTo(200);
    }

    @Test
    @DisplayName("원복 시도 자체도 실패해도 원래 예외(BusinessException)는 그대로 던져진다")
    void restoreFailureStillThrowsOriginalRejectionTest() {

        AbstractNode brokenNode = mock(AbstractNode.class,
                withSettings().extraInterfaces(Reconfigurable.class));
        Reconfigurable reconfigurable = (Reconfigurable) brokenNode;
        doThrow(new IllegalStateException("항상 실패")).when(reconfigurable).reconfigure(any());

        when(flowManager.getNode(FLOW_ID, NODE_ID)).thenReturn(brokenNode);
        when(flowManager.getNodeConfig(FLOW_ID, NODE_ID)).thenReturn(Map.of("threshold", 100));

        assertThatThrownBy(() ->
                flowConfigService.reconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 200)))
                .isInstanceOf(BusinessException.class);
    }
}