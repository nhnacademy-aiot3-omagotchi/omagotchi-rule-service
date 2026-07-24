package site.omagotchi.ruleservice.flow.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.omagotchi.ruleservice.core.engine.FlowManager;
import site.omagotchi.ruleservice.core.engine.dto.FlowSummary;
import site.omagotchi.ruleservice.flow.application.FlowConfigService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowManager flowManager;
    private final FlowConfigService flowConfigService;

    // GET /flows
    // 목록 (id, flowState, nodeIds)
    @GetMapping
    public ResponseEntity<List<FlowSummary>> getFlowList() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.listSummaries());
    }

    // GET /flows/{flowId}
    // 동작: 상세(구조 + 상태) -> 지금 뭐가 돌고 있는지 확인
    @GetMapping("/{flowId}")
    public ResponseEntity<FlowSummary> getFlow(@PathVariable String flowId) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    // POST /flows/{flowId}/start
    @PostMapping("/{flowId}/start")
    public ResponseEntity<FlowSummary> start(@PathVariable String flowId) {
        flowManager.start(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    // POST /flows/{flowId}/stop
    @PostMapping("/{flowId}/stop")
    public ResponseEntity<FlowSummary> stop(@PathVariable String flowId) {
        flowManager.stop(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    // POST /flows/{flowId}/restart
    @PostMapping("/{flowId}/restart")
    public ResponseEntity<FlowSummary> restart(@PathVariable String flowId) {
        flowManager.restart(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    // PATCH /flows/{flowId}/nodes/{nodeId}/config
    @PatchMapping("/{flowId}/nodes/{nodeId}/config")
    public ResponseEntity<Void> reconfigure(@PathVariable String flowId,
                                            @PathVariable String nodeId,
                                            @RequestBody Map<String, Object> config) {
        flowConfigService.reconfigure(flowId, nodeId, config);

        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .build();
    }
}