package site.omagotchi.ruleservice.distributed.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.distributed.application.MutableClock;
import site.omagotchi.ruleservice.distributed.application.port.EnginePresenceListener;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EngineDiscoveryServiceTest {

    private static final long OFFLINE_THRESHOLD_MS = 3_000L;
    private static final String PEER_URL = "http://peer-host:8082/api/v1/internal/engines/self";
    private static final String PEER_RESPONSE = """
            {"engineId":"engine-b","host":"peer-host","port":8082,"priority":2,"startedAt":0,"engineRole":"STANDBY"}
            """;

    private DiscoveryClient discoveryClient;
    private MockRestServiceServer restServiceServer;
    private MutableClock clock;
    private EnginePresenceListener listener;
    private EngineDiscoveryService engineDiscoveryService;
    private ServiceInstance peerInstance;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        this.discoveryClient = mock(DiscoveryClient.class);

        RestClient.Builder restClientBuilder = RestClient.builder();
        this.restServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        this.clock = new MutableClock(Instant.now());
        this.listener = mock(EnginePresenceListener.class);

        this.peerInstance = new DefaultServiceInstance(
                "peer-1", "rule-service", "peer-host", 8082, false,
                Map.of("engine-id", "engine-b", "engine-priority", "2")
        );

        this.engineDiscoveryService = new EngineDiscoveryService(
                this.discoveryClient,
                restClientBuilder.build(),
                "rule-service",
                "engine-a",
                List.of(this.listener),
                this.clock
        );

        this.logger = (Logger) LoggerFactory.getLogger(EngineDiscoveryService.class);
        this.logger.setLevel(Level.WARN);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void tearDown() {
        this.logger.detachAppender(this.appender);
    }

    @Test
    @DisplayName("피어를 처음 발견하면 폴링에 성공한 뒤 ONLINE으로 등록되고 알림이 온다")
    void discoversNewPeerAndNotifies() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers();

        List<EngineInfo> engines = this.engineDiscoveryService.listEngines();
        assertThat(engines).hasSize(1);
        assertThat(engines.get(0).engineId()).isEqualTo("engine-b");
        assertThat(engines.get(0).presenceStatus()).isEqualTo(PresenceStatus.ONLINE);
        verify(this.listener, times(1)).onPresenceChanged(); // 신규 발견 - 알림
    }

    @Test
    @DisplayName("계속 폴링에 성공하면 상태 변화가 없어 알림이 다시 오지 않는다")
    void staysOnlineWithoutNotificationWhenPollingKeepsSucceeding() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        // MockRestServiceServer는 요청이 한 번이라도 들어간 뒤엔 expect 추가가 불가능해서, 두 번의 폴링분을 미리 다 큐에 쌓아둠
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // 최초 발견 - 알림 1회

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS - 1));
        this.engineDiscoveryService.pollPeers(); // 여전히 ONLINE - 상태 변화 없음

        assertThat(this.engineDiscoveryService.listEngines().get(0).presenceStatus()).isEqualTo(PresenceStatus.ONLINE);
        verify(this.listener, times(1)).onPresenceChanged();
    }

    @Test
    @DisplayName("폴링이 계속 실패해서 OFFLINE_THRESHOLD_MS가 지나면 OFFLINE으로 판정되고 알림이 온다")
    void marksOfflineAfterThresholdElapsedWithoutSuccess() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withServerError());

        this.engineDiscoveryService.pollPeers(); // 최초 발견 - ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers(); // 폴링 실패

        assertThat(this.engineDiscoveryService.listEngines().get(0).presenceStatus()).isEqualTo(PresenceStatus.OFFLINE);
        verify(this.listener, times(2)).onPresenceChanged();
    }

    @Test
    @DisplayName("OFFLINE 판정 후 다시 폴링이 성공하면 ONLINE으로 복귀하고 알림이 온다")
    void marksOnlineAgainAfterSuccessfulPollFollowingOffline() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withServerError());
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers(); // OFFLINE

        this.engineDiscoveryService.pollPeers(); // 다시 성공 - ONLINE 복귀

        assertThat(this.engineDiscoveryService.listEngines().get(0).presenceStatus()).isEqualTo(PresenceStatus.ONLINE);
        verify(this.listener, times(3)).onPresenceChanged();
    }

    @Test
    @DisplayName("403(인증 실패)은 500과 다르게 OFFLINE_THRESHOLD_MS가 지나도 AUTH_FAILED로 유지된다")
    void marksAuthFailedOnForbiddenUnlikeServerError() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers(); // ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers(); // 403

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.AUTH_FAILED);
    }

    @Test
    @DisplayName("403이 연속으로 나도 인증 실패 경고 로그는 한 번만 찍힌다")
    void warnsOnceForConsecutiveForbidden() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers();
        this.engineDiscoveryService.pollPeers();
        this.engineDiscoveryService.pollPeers();

        long authWarnCount = this.appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("폴링 인증 실패"))
                .count();
        assertThat(authWarnCount).isEqualTo(1);
    }

    @Test
    @DisplayName("인증 실패 후 정상 응답이 오면, 다시 실패했을 때 재경고한다")
    void warnsAgainAfterRecoveryThenFailingAgain() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers(); // 403 - 1차 경고
        this.engineDiscoveryService.pollPeers(); // 성공 - authFailureWarned 초기화
        this.engineDiscoveryService.pollPeers(); // 403 - 2차 경고

        long authWarnCount = this.appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("폴링 인증 실패"))
                .count();
        assertThat(authWarnCount).isEqualTo(2);
    }

    @Test
    @DisplayName("AUTH_FAILED였다가 폴링이 성공하면 즉시 ONLINE으로 복귀한다")
    void recoversToOnlineImmediatelyAfterAuthFailure() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // AUTH_FAILED
        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.AUTH_FAILED);

        this.engineDiscoveryService.pollPeers(); // 시크릿 복구 - 성공
        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.ONLINE);
    }

    @Test
    @DisplayName("Eureka가 이번 주기에 빈 목록을 줘도(discovery-service 장애 흉내), 이미 아는 피어는 직접 폴링을 이어가 ONLINE을 유지한다")
    void continuesPollingKnownPeerWhenEurekaReturnsEmpty() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance))
                .thenReturn(List.of()); // 두 번째 주기부터 Eureka가 빈 목록

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // 1주기 - Eureka로 발견, ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS - 1));
        this.engineDiscoveryService.pollPeers(); // 2주기 - Eureka는 빈 목록이지만 폴백 폴링으로 여전히 성공

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.ONLINE);
    }

    @Test
    @DisplayName("presenceStatus는 그대로여도 피어의 engineRole이 바뀌면 알림이 온다")
    void notifiesWhenPeerRoleChangesEvenIfPresenceStaysOnline() {
        String activeResponse = """
                {"engineId":"engine-b","host":"peer-host","port":8082,"priority":2,"startedAt":0,"engineRole":"ACTIVE"}
                """;
        String standbyResponse = """
                {"engineId":"engine-b","host":"peer-host","port":8082,"priority":2,"startedAt":0,"engineRole":"STANDBY"}
                """;

        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(activeResponse, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(activeResponse, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(standbyResponse, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // 최초 발견 - 알림 1회 (role=ACTIVE)
        this.engineDiscoveryService.pollPeers(); // presence·role 모두 그대로 - 알림 없음
        this.engineDiscoveryService.pollPeers(); // presence는 그대로, role만 ACTIVE -> STANDBY - 알림

        assertThat(this.engineDiscoveryService.listEngines().getFirst().engineRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.listener, times(2)).onPresenceChanged(); // 최초 발견 1회 + role 변화 1회 (presence 자체는 안 바뀜)
    }

    @Test
    @DisplayName("discovery-service 장애로 폴백 경로를 타면서 403이 나도, 이미 알던 피어의 priority가 유지된다")
    void preservesKnownPriorityOnAuthFailedViaFallbackPath() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance))
                .thenReturn(List.of()); // 두 번째 주기부터 Eureka 장애 흉내 (discovery-service 장애 + 시크릿 불일치 조합)

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers(); // 1주기 - 정상 발견, priority=2 확인됨
        assertThat(this.engineDiscoveryService.listEngines().getFirst().priority()).isEqualTo(2);

        this.engineDiscoveryService.pollPeers(); // 2주기 - Eureka 빈 목록(폴백 경로, metadata 없음) + 403

        EngineInfo peer = this.engineDiscoveryService.listEngines().getFirst();
        assertThat(peer.presenceStatus()).isEqualTo(PresenceStatus.AUTH_FAILED);
        assertThat(peer.priority()).isEqualTo(2); // 수정 전엔 Integer.MAX_VALUE로 깨졌음
    }

    @Test
    @DisplayName("engine-priority metadata가 숫자가 아니어도 예외 없이 최하위 우선순위로 처리한다")
    void parsePriorityFallsBackOnMalformedMetadata() {
        ServiceInstance malformedInstance = new DefaultServiceInstance(
                "peer-1", "rule-service", "peer-host", 8082, false,
                Map.of("engine-id", "engine-b", "engine-priority", "not-a-number")
        );
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(malformedInstance));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatCode(() -> this.engineDiscoveryService.pollPeers()).doesNotThrowAnyException();

        assertThat(this.engineDiscoveryService.listEngines().getFirst().priority()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("AUTH_FAILED 상태에서 연결 실패가 OFFLINE_THRESHOLD_MS 안이면 아직 AUTH_FAILED를 유지한다")
    void staysAuthFailedWhenConnectionFailureIsWithinThreshold() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withServerError());

        this.engineDiscoveryService.pollPeers(); // 403 (AUTH_FAILED)

        this.clock.advance(Duration.ofMillis(2_000)); // 실제 OFFLINE_THRESHOLD_MS(3000ms)보다 짧게
        this.engineDiscoveryService.pollPeers(); // 연결 실패, 아직 threshold 안 지남

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.AUTH_FAILED);
    }

    @Test
    @DisplayName("AUTH_FAILED 상태에서 403조차 못 받고 OFFLINE_THRESHOLD_MS 이상 지나면 OFFLINE으로 전환하고 알림이 온다")
    void transitionsAuthFailedToOfflineWhenNoForbiddenResponseForThreshold() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withServerError());

        this.engineDiscoveryService.pollPeers(); // 403 - AUTH_FAILED, 알림 1회(최초 발견)

        this.clock.advance(Duration.ofMillis(3_001L)); // 실제 OFFLINE_THRESHOLD_MS(3000ms) 초과
        this.engineDiscoveryService.pollPeers(); // 연결 실패, 마지막 403 이후 threshold 초과 - OFFLINE 전환

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.OFFLINE);
        verify(this.listener, times(2)).onPresenceChanged(); // 최초 발견 1회 + OFFLINE 전환 1회
    }

    @Test
    @DisplayName("403을 계속 받고 있으면 타임스탬프가 매번 갱신되어, 뒤늦은 연결 실패 한 번만으로 바로 OFFLINE 처리되지 않는다")
    void refreshesAuthFailedTimestampOnEachForbiddenResponse() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withServerError());

        this.engineDiscoveryService.pollPeers(); // 1차 403 - lastAuthFailedAt 기록

        this.clock.advance(Duration.ofMillis(3_001L)); // 실제 threshold 초과 경과
        this.engineDiscoveryService.pollPeers(); // 2차 403 - 여전히 살아있음, lastAuthFailedAt 갱신돼야 함

        this.clock.advance(Duration.ofMillis(2_000L)); // 방금 갱신된 시각 기준으론 아직 threshold(3000ms) 안 지남
        this.engineDiscoveryService.pollPeers(); // 연결 실패 - 갱신이 제대로 안 됐다면(putIfAbsent 버그) 여기서 OFFLINE으로 잘못 전환됐을 것

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.AUTH_FAILED);
    }
}
