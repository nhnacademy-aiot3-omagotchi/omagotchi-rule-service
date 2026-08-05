package site.omagotchi.ruleservice.distributed.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.omagotchi.ruleservice.distributed.application.EngineIdentityResolver;
import site.omagotchi.ruleservice.distributed.presentation.response.EngineSelfResponse;

@RestController
@RequestMapping("/api/v1/engines")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineController {

    private final EngineIdentityResolver engineIdentityResolver;

    @GetMapping("/self")
    public ResponseEntity<EngineSelfResponse> self() {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(EngineSelfResponse.from(this.engineIdentityResolver.getSelf()));
    }
}