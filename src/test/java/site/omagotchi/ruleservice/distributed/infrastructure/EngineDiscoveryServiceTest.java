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
import org.springframework.test.web.client.ExpectedCount;
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
import static site.omagotchi.ruleservice.distributed.infrastructure.EngineDiscoveryService.OFFLINE_THRESHOLD_MS;
import static site.omagotchi.ruleservice.distributed.infrastructure.EngineDiscoveryService.PEER_EXPIRY_MS;

class EngineDiscoveryServiceTest {

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

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.OFFLINE);
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

    @Test
    @DisplayName("AUTH_FAILED에서 폴링이 성공해 ONLINE으로 복구되면 리스너에게 알림이 간다")
    void notifiesWhenPeerRecoversFromAuthFailedToOnline() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // 최초 발견 + AUTH_FAILED - 알림 1회
        this.engineDiscoveryService.pollPeers(); // 시크릿 복구 - ONLINE 전이라 알림이 또 와야 함

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus())
                .isEqualTo(PresenceStatus.ONLINE);
        verify(this.listener, times(2)).onPresenceChanged();
    }

    @Test
    @DisplayName("ONLINE이던 피어가 403을 받아 AUTH_FAILED로 바뀌면 리스너에게 알림이 간다")
    void notifiesWhenPeerTransitionsFromOnlineToAuthFailed() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers(); // 최초 발견 - 알림 1회
        this.engineDiscoveryService.pollPeers(); // 403 - AUTH_FAILED 전이라 알림이 또 와야 함

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus())
                .isEqualTo(PresenceStatus.AUTH_FAILED);
        verify(this.listener, times(2)).onPresenceChanged();
    }

    @Test
    @DisplayName("403이 연속으로 나면 첫 전이 때만 알리고 그 뒤로는 중복 알림하지 않는다")
    void doesNotNotifyRepeatedlyWhileStayingAuthFailed() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers(); // 최초 발견 + AUTH_FAILED - 알림 1회
        this.engineDiscoveryService.pollPeers();
        this.engineDiscoveryService.pollPeers();

        verify(this.listener, times(1)).onPresenceChanged();
    }

    @Test
    @DisplayName("폴링 응답의 engineId가 Eureka 메타데이터와 다르면 그 응답의 priority/role을 신뢰하지 않는다")
    void ignoresPeerClaimsWhenResponseEngineIdDoesNotMatchMetadata() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        // 메타데이터는 engine-id=engine-b인데, 응답은 다른 엔진을 자칭하며 최상위 우선순위/액티브를 주장
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess("""
                        {"engineId":"사칭하는 엔진","host":"peer-host","port":8082,"priority":0,"startedAt":0,"engineRole":"ACTIVE"}
                        """, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers();

        EngineInfo stored = this.engineDiscoveryService.listEngines().getFirst();

        assertThat(stored.engineId()).isEqualTo("engine-b");
        assertThat(stored.priority()).isEqualTo(2); // 사칭 응답 0이 아니라 메타데이터 값
        assertThat(stored.engineRole()).isNull(); // 사칭 응답의 액티브를 반영 안 함
    }

    @Test
    @DisplayName("신원 불일치가 이어지면 진짜 피어에 닿지 못한 것으로 보고 임계 후 OFFLINE으로 판정한다")
    void marksOfflineWhenIdentityMismatchPersists() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        String impostor = """
                {"engineId":"evil-engine","host":"peer-host","port":8082,"priority":0,"startedAt":0,"engineRole":"ACTIVE"}
                """;
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withSuccess(impostor, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL)).andRespond(withSuccess(impostor, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // 첫 발견 - 유예

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers();

        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.OFFLINE);

        long mismatchCount = this.appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("Eureka 메타데이터와 다름"))
                .count();
        assertThat(mismatchCount).isEqualTo(1); // 반복돼도 경고는 한 번만
    }

    @Test
    @DisplayName("Eureka에서 사라지고 OFFLINE으로 충분히 오래 지난 피어는 목록에서 제거된다")
    void expiresPeerLongGoneFromRegistry() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance))
                .thenReturn(List.of()); // 의도적 스케일다운 - registry에서 사라짐

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(ExpectedCount.manyTimes(), requestTo(PEER_URL))
                .andRespond(withServerError()); // 이후 폴백 폴링은 계속 실패

        this.engineDiscoveryService.pollPeers(); // 발견 - ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers(); // OFFLINE 판정

        assertThat(this.engineDiscoveryService.listEngines()).hasSize(1); // 아직은 목록에 남아 있음

        this.clock.advance(Duration.ofMillis(PEER_EXPIRY_MS));
        this.engineDiscoveryService.pollPeers(); // 만료

        assertThat(this.engineDiscoveryService.listEngines()).isEmpty();
    }

    @Test
    @DisplayName("Eureka 조회 자체가 실패하는 동안에는 피어를 만료시키지 않는다")
    void doesNotExpireWhileRegistryFetchFails() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance))
                .thenThrow(new IllegalStateException("discovery-service 장애")); // 이후 계속 실패

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(ExpectedCount.manyTimes(), requestTo(PEER_URL))
                .andRespond(withServerError());

        this.engineDiscoveryService.pollPeers(); // 발견 - ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers(); // OFFLINE 판정

        this.clock.advance(Duration.ofMillis(PEER_EXPIRY_MS));
        this.engineDiscoveryService.pollPeers();

        // discovery-service 장애를 스케일다운으로 오해하면 주소를 잃어버려 폴백 폴링까지 끊김
        assertThat(this.engineDiscoveryService.listEngines()).hasSize(1);
    }

    @Test
    @DisplayName("Eureka 장애가 길어져도 그 시간은 만료에 산입되지 않고, 복구 후 다시 처음부터 센다")
    void doesNotCountRegistryOutageTowardExpiry() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance))
                .thenThrow(new IllegalStateException("discovery-service 장애"))
                .thenReturn(List.of()); // 복구 - 피어는 정말로 사라진 상태

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(ExpectedCount.manyTimes(), requestTo(PEER_URL))
                .andRespond(withServerError());

        this.engineDiscoveryService.pollPeers(); // 발견 - ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + PEER_EXPIRY_MS));
        this.engineDiscoveryService.pollPeers(); // Eureka 장애 구간 - 시간만 크게 흐름

        this.engineDiscoveryService.pollPeers(); // 복구 직후 첫 조회

        // 장애 구간이 만료에 산입됐다면 여기서 이미 지워졌을 것
        assertThat(this.engineDiscoveryService.listEngines()).hasSize(1);
    }

    @Test
    @DisplayName("registry에서 사라져도 폴백 폴링으로 ONLINE을 유지 중이면 만료시키지 않는다")
    void doesNotExpireStillOnlinePeer() {
        when(this.discoveryClient.getInstances("rule-service"))
                .thenReturn(List.of(this.peerInstance))
                .thenReturn(List.of());

        this.restServiceServer.expect(ExpectedCount.manyTimes(), requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));

        this.engineDiscoveryService.pollPeers(); // 발견 - ONLINE

        this.clock.advance(Duration.ofMillis(PEER_EXPIRY_MS + 1));
        this.engineDiscoveryService.pollPeers(); // 폴백 폴링은 계속 성공 -> ONLINE 유지

        assertThat(this.engineDiscoveryService.listEngines()).hasSize(1);
        assertThat(this.engineDiscoveryService.listEngines().getFirst().presenceStatus()).isEqualTo(PresenceStatus.ONLINE);
    }
}
