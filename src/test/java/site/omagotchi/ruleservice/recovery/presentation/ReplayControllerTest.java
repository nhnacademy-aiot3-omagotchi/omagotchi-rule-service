package site.omagotchi.ruleservice.recovery.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.omagotchi.ruleservice.recovery.application.ReplayService;
import site.omagotchi.ruleservice.recovery.domain.ReplayResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReplayControllerTest {

    @Mock
    ReplayService replayService;

    ReplayController replayController;

    @BeforeEach
    void setUp() {
        replayController = new ReplayController(replayService);
    }

    @Test
    @DisplayName("요청 건수를 그대로 넘기고 결과를 200으로 리턴한다")
    void replayTest() {
        ReplayResult result = new ReplayResult(12);
        when(replayService.replay(100)).thenReturn(result);

        ResponseEntity<ReplayResult> response = replayController.replay(100);

        verify(replayService).replay(100);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(result);
    }

    @Test
    @DisplayName("max가 하한 미만이면 400이고 replay가 실행되지 않는다")
    void rejectsMaxBelowLowerBoundTest() throws Exception {
        mockMvc().perform(post("/api/v1/recovery/replay").param("max", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(replayService);
    }

    @Test
    @DisplayName("max가 상한을 넘으면 400이고 replay가 실행되지 않는다")
    void rejectsMaxAboveUpperBoundTest() throws Exception {
        mockMvc().perform(post("/api/v1/recovery/replay").param("max", "1001"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(replayService);
    }

    /** 검증은 스프링 MVC가 호출 시점에 수행하므로, 직접 호출이 아닌 요청 경로로 확인한다. */
    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(replayController).build();
    }
}
