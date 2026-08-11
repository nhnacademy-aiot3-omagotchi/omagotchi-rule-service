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
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EngineDiscoveryServiceTest {

    private static final long OFFLINE_THRESHOLD_MS = 12_000L;
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
    @DisplayName("403(인증 실패)도 OFFLINE_THRESHOLD_MS가 지나면 500과 동일하게 OFFLINE으로 판정된다")
    void marksOfflineOnForbiddenSameAsServerError() {
        when(this.discoveryClient.getInstances("rule-service")).thenReturn(List.of(this.peerInstance));

        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withSuccess(PEER_RESPONSE, MediaType.APPLICATION_JSON));
        this.restServiceServer.expect(requestTo(PEER_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        this.engineDiscoveryService.pollPeers(); // ONLINE

        this.clock.advance(Duration.ofMillis(OFFLINE_THRESHOLD_MS + 1));
        this.engineDiscoveryService.pollPeers(); // 403 - 폴링 실패와 동일 취급

        assertThat(this.engineDiscoveryService.listEngines().get(0).presenceStatus()).isEqualTo(PresenceStatus.OFFLINE);
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
}
