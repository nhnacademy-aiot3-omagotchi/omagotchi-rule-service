package site.omagotchi.ruleservice.recovery.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.omagotchi.ruleservice.recovery.application.ReplayService;
import site.omagotchi.ruleservice.recovery.domain.ReplayResult;

@RequestMapping("/api/v1/recovery")
@RequiredArgsConstructor
@RestController
public class ReplayController {

    private final ReplayService replayService;

    @PostMapping("/replay")
    public ResponseEntity<ReplayResult> replay(@RequestParam(defaultValue = "100") int max){
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(replayService.replay(max));
    }

}
