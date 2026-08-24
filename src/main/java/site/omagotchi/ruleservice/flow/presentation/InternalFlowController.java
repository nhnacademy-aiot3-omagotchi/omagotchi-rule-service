package site.omagotchi.ruleservice.flow.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.omagotchi.ruleservice.flow.application.FlowConfigService;
import site.omagotchi.ruleservice.flow.application.FlowManager;

import java.util.Map;

/**
 * 파트너 엔진(PeerFlowSyncClient)이 호출하는 내부 통신 전용 엔드포인트
 * -> 로컬 적용만 하고 재전달X (무한루프 방지는 FlowManager/FlowConfigService의 xxxFromPeer가 보장)
 * -> Gateway에서 외부 노출을 막아야 함
 */
@RestController
@RequestMapping("/api/v1/internal/flows")
@RequiredArgsConstructor
public class InternalFlowController {

    private final FlowManager flowManager;
    private final FlowConfigService flowConfigService;

    @PostMapping("/{flow-id}/start")
    public ResponseEntity<Void> startFromPeer(@PathVariable("flow-id") String flowId) {
        this.flowManager.startFromPeer(flowId);

        return ResponseEntity
                .noContent()
                .build();
    }

    @PostMapping("/{flow-id}/stop")
    public ResponseEntity<Void> stopFromPeer(@PathVariable("flow-id") String flowId) {
        this.flowManager.stopFromPeer(flowId);

        return ResponseEntity
                .noContent()
                .build();
    }

    @PostMapping("/{flow-id}/restart")
    public ResponseEntity<Void> restartFromPeer(@PathVariable("flow-id") String flowId) {
        this.flowManager.restartFromPeer(flowId);

        return ResponseEntity
                .noContent()
                .build();
    }

    @PatchMapping("/{flow-id}/nodes/{node-id}/config")
    public ResponseEntity<Void> reconfigureFromPeer(@PathVariable("flow-id") String flowId,
                                                    @PathVariable("node-id") String nodeId,
                                                    @RequestBody Map<String, Object> config) {

        this.flowConfigService.reconfigureFromPeer(flowId, nodeId, config);

        return ResponseEntity
                .noContent()
                .build();
    }
}
