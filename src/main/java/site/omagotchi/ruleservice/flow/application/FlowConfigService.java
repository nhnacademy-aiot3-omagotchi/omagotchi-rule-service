package site.omagotchi.ruleservice.flow.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.flow.application.port.PeerFlowSyncPort;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Reconfigurable;
import site.omagotchi.ruleservice.global.exception.BusinessException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class FlowConfigService {

    private final FlowManager flowManager;
    private final PeerFlowSyncPort peerFlowSyncPort;

    // key: "flowId:nodeId" -> 이 서비스가 마지막으로 성공시킨 config를 스스로 기억
    private final Map<String, Map<String, Object>> appliedConfigs = new ConcurrentHashMap<>();

    /**
     * 공개 PATCH 엔드포인트 전용 -> 로컬 적용 후 파트너에게도 전달
     */
    public synchronized void reconfigure(String flowId, String nodeId, Map<String, Object> newConfig) {
        this.applyReconfigureLocally(flowId, nodeId, newConfig); // 로컬 적용
        this.peerFlowSyncPort.syncReconfigure(flowId, nodeId, newConfig); // 파트너에게 전달 (내부 통신)
    }

    /**
     * 내부 전용 엔드포인트 전용 -> 파트너가 이미 결정한 걸 로컬에만 적용, 재전달X (무한루프 방지)
     */
    public synchronized void applyReconfigureFromPeer(String flowId, String nodeId, Map<String, Object> newConfig) {
        this.applyReconfigureLocally(flowId, nodeId, newConfig);
    }

    /**
     * synchronized가 붙으면서 인스턴스 전체(이 서비스 Bean 하나)를 락으로 쓰는거라서, 서로 다른 플로우/노드에 대한 PATCH도 이 메서드 안에서는 직렬화됨
     * -> PATCH는 자주 운영되는 게 아니라 일단 이렇게 하기는 했으나, 혹시 나중에 이 부분이 병목이 되면 그때 노드별 락으로 세분화
     */
    private void applyReconfigureLocally(String flowId, String nodeId, Map<String, Object> newConfig) {
        AbstractNode node = flowManager.getNode(flowId, nodeId); // 없으면 BusinessException(404)

        if (!(node instanceof Reconfigurable reconfigurable)) {
            throw new BusinessException(FlowErrorCode.NODE_NOT_RECONFIGURABLE, "flowId = %s, nodeId = %s"
                    .formatted(flowId, nodeId));
        }

        String key = flowId + ":" + nodeId;

        // 이 노드의 첫 PATCH라면 정적 정의값을 스냅샷의 시작점으로 삼음
        // computeIfAbsent: 키가 이미 있으면 그 값 그대로 리턴, 없으면 두 번째 인자(람다)를 실행해서 그 결과를 새로 넣고 리턴
        Map<String, Object> snapshot = appliedConfigs.computeIfAbsent(
                key, k -> flowManager.getNodeConfig(flowId, nodeId)
        );

        try {
            // 실제로 값을 바꾸는 그 노드 자신의 메서드 호출 (지금 이 메서드 재귀 아님 헷갈림 주의)
            reconfigurable.reconfigure(newConfig);

            // newConfig 방어적 복사
            // Map.copyOf(newConfig) 쓰면 안 됨 -> key나 value에 null이 하나라도 있으면 NPE 남 (config에 null이 올 수 있음)
            // Collection.unmodifiableMap(new HashMap<>(newConfig)) -> null 허용
            // PATCH 바디에 null값이 있으면 Map.copyOf가 NPE 터져서 방금 성공한 reconfigure를 엉뚱하게 롤백시킴
            appliedConfigs.put(key, Collections.unmodifiableMap(new HashMap<>(newConfig)));
            log.info("[flow={}, node={}] 무중단 config 변경 적용: {}", flowId, nodeId, newConfig);
        } catch (RuntimeException e) {
            log.error("[flow={}, node={}] config 적용 실패 - 이전 값으로 원복 시도: {}", flowId, nodeId, newConfig, e);
            this.restore(flowId, nodeId, reconfigurable, snapshot); // 방어적 원복 시도

            throw new BusinessException(FlowErrorCode.NODE_CONFIG_REJECTED,
                    "flowId = %s, nodeId = %s, reason = %s".formatted(flowId, nodeId, e.getMessage()));
        }
    }

    // 방어적 원복 - reconfigure 구현체가 원자성 규칙 지켰다면 보통 호출될 일 없음
    private void restore(String flowId, String nodeId, Reconfigurable reconfigurable, Map<String, Object> snapshot) {
        try {
            reconfigurable.reconfigure(snapshot);
            log.info("[flow={}, node={}] 이전 값으로 원복 완료: {}", flowId, nodeId, snapshot);
        } catch (RuntimeException restoreFailure) {
            log.error("[flow={}, node={}] 원복도 실패 - 노드가 불안정한 상태일 수 있습니다: {}", flowId, nodeId, snapshot, restoreFailure);
        }
    }
}