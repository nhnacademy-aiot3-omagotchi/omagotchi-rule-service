package site.omagotchi.ruleservice.flow.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.presentation.response.FlowSummary;
import site.omagotchi.ruleservice.flow.application.FlowConfigService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowManager flowManager;
    private final FlowConfigService flowConfigService;

    // 목록 (id, flowState, nodeIds)
    @GetMapping
    public ResponseEntity<List<FlowSummary>> getFlowList() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.listSummaries());
    }

    // 동작: 상세(구조 + 상태) -> 지금 뭐가 돌고 있는지 확인
    @GetMapping("/{flow-id}")
    public ResponseEntity<FlowSummary> getFlow(@PathVariable("flow-id") String flowId) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    @PostMapping("/{flow-id}/start")
    public ResponseEntity<FlowSummary> start(@PathVariable("flow-id") String flowId) {
        flowManager.start(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    @PostMapping("/{flow-id}/stop")
    public ResponseEntity<FlowSummary> stop(@PathVariable("flow-id") String flowId) {
        flowManager.stop(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    @PostMapping("/{flow-id}/restart")
    public ResponseEntity<FlowSummary> restart(@PathVariable("flow-id") String flowId) {
        flowManager.restart(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    @PatchMapping("/{flow-id}/nodes/{node-id}/config")
    public ResponseEntity<Void> reconfigure(@PathVariable("flow-id") String flowId,
                                            @PathVariable("node-id") String nodeId,
                                            @RequestBody Map<String, Object> config) {
        flowConfigService.reconfigure(flowId, nodeId, config);

        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .build();
    }
}