package site.omagotchi.ruleservice.distributed.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.omagotchi.ruleservice.distributed.application.EngineIdentityResolver;
import site.omagotchi.ruleservice.distributed.application.EngineRoleService;
import site.omagotchi.ruleservice.distributed.presentation.response.EngineSummaryResponse;

@RestController
@RequestMapping("/api/v1/internal/engines")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class InternalEngineController {

    private final EngineIdentityResolver engineIdentityResolver;
    private final EngineRoleService engineRoleService;

    @GetMapping("/self")
    public ResponseEntity<EngineSummaryResponse> self() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(EngineSummaryResponse.from(
                        this.engineIdentityResolver.getSelf(), // EngineInfo
                        this.engineRoleService.getCurrentRole() // EngineRole
                ));
    }
}
