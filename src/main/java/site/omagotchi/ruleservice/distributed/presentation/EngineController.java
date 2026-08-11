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
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.presentation.response.EngineSummaryResponse;

import java.util.ArrayList;
import java.util.List;

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
    private final EngineRoleService engineRoleService;
    private final EngineDirectoryPort engineDirectoryPort;

    @GetMapping
    public ResponseEntity<List<EngineSummaryResponse>> engineList() {
        List<EngineSummaryResponse> engines = new ArrayList<>();

        // 자기 자신은 매번 최신 engineRole로 직접 조합 (EngineIdentityResolver.self는 engineRole을 안 담고 있음)
        engines.add(EngineSummaryResponse.from(
                this.engineIdentityResolver.getSelf(),
                this.engineRoleService.getCurrentRole()
        ));

        // 피어는 이미 폴링으로 받아둔 EngineInfo.engineRole()을 그대로 사용 (피어가 자기소개 때 보고한 값)
        for (EngineInfo peer : this.engineDirectoryPort.listEngines()) {
            engines.add(EngineSummaryResponse.from(
                    peer,
                    peer.engineRole()
            ));
        }

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(engines);
    }
}
