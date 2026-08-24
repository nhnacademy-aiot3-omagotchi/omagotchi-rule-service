package site.omagotchi.ruleservice.distributed.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.omagotchi.ruleservice.distributed.application.TopologyService;
import site.omagotchi.ruleservice.distributed.presentation.response.FlowTopologyResponse;

@RestController
@RequestMapping("/api/v1/flows")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class FlowTopologyController {

    private final TopologyService topologyService;

    @GetMapping("/{flow-id}/topology")
    public ResponseEntity<FlowTopologyResponse> topology(@PathVariable("flow-id") String flowId) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(this.topologyService.getTopology(flowId));
    }
}
