package site.omagotchi.ruleservice.flow.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.omagotchi.ruleservice.core.engine.FlowManager;
import site.omagotchi.ruleservice.core.engine.dto.FlowSummary;

import java.util.List;

@RestController
@RequestMapping("/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowManager flowManager;

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

    @PostMapping("/{flowId}/stop")
    public ResponseEntity<FlowSummary> stop(@PathVariable String flowId) {
        flowManager.stop(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }

    @PostMapping("/{flowId}/restart")
    public ResponseEntity<FlowSummary> restart(@PathVariable String flowId) {
        flowManager.restart(flowId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(flowManager.getSummary(flowId));
    }
}