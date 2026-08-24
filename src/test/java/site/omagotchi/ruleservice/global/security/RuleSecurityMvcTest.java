package site.omagotchi.ruleservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import site.omagotchi.ruleservice.distributed.application.EngineIdentityResolver;
import site.omagotchi.ruleservice.distributed.application.EngineRoleService;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.distributed.presentation.EngineController;
import site.omagotchi.ruleservice.flow.application.FlowConfigService;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.domain.FlowState;
import site.omagotchi.ruleservice.flow.presentation.FlowController;
import site.omagotchi.ruleservice.flow.presentation.response.FlowSummary;
import site.omagotchi.ruleservice.recovery.application.ReplayService;
import site.omagotchi.ruleservice.recovery.domain.ReplayResult;
import site.omagotchi.ruleservice.recovery.presentation.ReplayController;
import site.omagotchi.ruleservice.rule.domain.RuleCache;
import site.omagotchi.ruleservice.rule.presentation.RuleController;
import site.omagotchi.ruleservice.rule.presentation.RulePingController;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {
                FlowController.class,
                RuleController.class,
                RulePingController.class,
                EngineController.class,
                ReplayController.class
        },
        properties = {
                "spring.application.name=rule-service",
                "eureka.client.enabled=true" // EngineController의 @ConditionalOnProperty 통과시키기 위함
        }
)
@Import({
        SecurityConfig.class,
        JwtConfig.class,
        JwtAuthorityConfig.class,
        SecurityErrorResponseHandler.class,
        TestJwtKeyConfig.class
})
@EnableConfigurationProperties({
        JwtProperties.class,
        InternalAuthProperties.class
})
@ActiveProfiles("test")
class RuleSecurityMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuleCache ruleCache;

    @MockitoBean
    private FlowManager flowManager;

    @MockitoBean
    private FlowConfigService flowConfigService;

    @MockitoBean
    private EngineIdentityResolver engineIdentityResolver;

    @MockitoBean
    private EngineRoleService engineRoleService;

    @MockitoBean
    private EngineDirectoryPort engineDirectoryPort;

    @MockitoBean
    private ReplayService replayService;

    @Test
    @DisplayName("정확한 Rule ping 경로는 Access JWT 없이 호출")
    void permitsExactRulePingWithoutToken() throws Exception {
        // When
        ResultActions result = mockMvc.perform(get("/api/v1/rules/ping"));

        // Then
        result
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("rule-service"))
                .andExpect(jsonPath("$.status").value("UP"));
        verifyNoInteractions(ruleCache);
    }

    @Test
    @DisplayName("Rule 운영 API는 Access JWT가 없으면 401")
    void rejectsProtectedRuleRequestWithoutToken() throws Exception {
        // Given
        String requestId = "rule-security-401";

        // When
        ResultActions result = mockMvc.perform(get("/api/v1/rules")
                .header("X-Request-ID", requestId));

        // Then
        result
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", requestId))
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        startsWith("Bearer")
                ))
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.path").value("/api/v1/rules"))
                .andExpect(jsonPath("$.requestId").value(requestId));
        verifyNoInteractions(ruleCache);
    }

    @Test
    @DisplayName("외부 사용자 Header를 붙여도 USER의 Flow 운영 API 접근은 403")
    void rejectsUserFromOperationsApiDespiteSpoofedHeaders() throws Exception {
        // Given
        String userToken = TestJwtKeyConfig.issue("USER");

        // When
        ResultActions result = mockMvc.perform(get("/api/v1/flows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .header("X-User-Id", TestJwtKeyConfig.USER_ID)
                .header("X-Global-Role", "SYSTEM_ADMIN"));

        // Then
        result
                .andExpect(status().isForbidden())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        containsString("error=\"insufficient_scope\"")
                ))
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_DENIED"));
        verifyNoInteractions(flowManager, flowConfigService);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN은 Flow 조회와 시작 API 호출")
    void permitsSystemAdminFlowRequests() throws Exception {
        // Given
        FlowSummary summary = new FlowSummary(
                "flow-1",
                FlowState.RUNNING,
                List.of("node-1")
        );
        given(flowManager.listSummaries()).willReturn(List.of(summary));
        given(flowManager.getSummary("flow-1")).willReturn(summary);
        String adminToken = TestJwtKeyConfig.issue("SYSTEM_ADMIN");

        // When
        ResultActions getResult = mockMvc.perform(get("/api/v1/flows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));
        ResultActions startResult = mockMvc.perform(post("/api/v1/flows/flow-1/start")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));

        // Then
        getResult.andExpect(status().isOk());
        startResult.andExpect(status().isOk());
        verify(flowManager).listSummaries();
        verify(flowManager).start("flow-1");
        verify(flowManager).getSummary("flow-1");
    }

    @Test
    @DisplayName("ping 하위 경로는 공개하지 않음")
    void doesNotPermitRulePingSubpath() throws Exception {
        // When
        ResultActions result = mockMvc.perform(get("/api/v1/rules/ping/extra"));

        // Then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("명시하지 않은 API는 인증돼도 403")
    void deniesUnlistedApiByDefault() throws Exception {
        // Given
        String adminToken = TestJwtKeyConfig.issue("SYSTEM_ADMIN");

        // When
        ResultActions result = mockMvc.perform(get("/api/v1/unlisted")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));

        // Then
        result
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Engine 목록 조회는 Access JWT가 없으면 401")
    void rejectEngineListWithoutToken() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/v1/engines"));

        result
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(engineIdentityResolver, engineDirectoryPort, engineRoleService);
    }

    @Test
    @DisplayName("외부 사용자 Header를 붙여도 USER의 Engine 목록 조회는 403")
    void rejectsUserFromEngineListDespiteSpoofedHeaders() throws Exception {
        String userToken = TestJwtKeyConfig.issue("USER");

        ResultActions result = mockMvc.perform(get("/api/v1/engines")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .header("X-Global-Role", "SYSTEM_ADMIN"));

        result
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_DENIED"));
        verifyNoInteractions(engineIdentityResolver, engineDirectoryPort, engineRoleService);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN은 Engine 목록을 조회할 수 있다")
    void permitsSystemAdminEngineListRequest() throws Exception {
        EngineInfo self = new EngineInfo(
                "engine-a", "localhost", 8081, 1, 0L,
                PresenceStatus.SELF, null
        );
        given(engineIdentityResolver.getSelf()).willReturn(self);
        given(engineRoleService.getCurrentRole()).willReturn(EngineRole.ACTIVE);
        given(engineDirectoryPort.listEngines()).willReturn(List.of());
        String adminToken = TestJwtKeyConfig.issue("SYSTEM_ADMIN");

        ResultActions result = mockMvc.perform(get("/api/v1/engines")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));

        result.andExpect(status().isOk());
        verify(engineDirectoryPort).listEngines();
    }

    @Test
    @DisplayName("Replay API는 Access JWT가 없으면 401")
    void rejectsReplayWithoutToken() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/v1/recovery/replay"));

        result
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(replayService);
    }

    @Test
    @DisplayName("외부 사용자 Header를 붙여도 USER의 Replay 호출은 403")
    void rejectsUserFromReplayDespiteSpoofedHeaders() throws Exception {
        String userToken = TestJwtKeyConfig.issue("USER");

        ResultActions result = mockMvc.perform(post("/api/v1/recovery/replay")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .header("X-Global-Role", "SYSTEM_ADMIN"));

        result
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_DENIED"));
        verifyNoInteractions(replayService);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN은 Replay를 호출할 수 있다")
    void permitsSystemAdminReplayRequest() throws Exception {
        given(replayService.replay(100)).willReturn(new ReplayResult(0));
        String adminToken = TestJwtKeyConfig.issue("SYSTEM_ADMIN");

        ResultActions result = mockMvc.perform(post("/api/v1/recovery/replay")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));

        result.andExpect(status().isOk());
        verify(replayService).replay(100);
    }
}
