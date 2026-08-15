package site.omagotchi.ruleservice.distributed.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class PeerFlowSyncClientTest {

    private static final String FLOW_ID = "flow-1";
    private static final String NODE_ID = "node-1";

    private EngineDirectoryPort engineDirectoryPort;
    private MockRestServiceServer restServiceServer;
    private PeerFlowSyncClient peerFlowSyncClient;

    private EngineInfo peerB;

    @BeforeEach
    void setUp() {
        this.engineDirectoryPort = mock(EngineDirectoryPort.class);

        RestClient.Builder restClientBuilder = RestClient.builder();
        this.restServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        this.peerB = new EngineInfo(
                "engine-b", "peer-host", 8082, 2, 0L, PresenceStatus.ONLINE, EngineRole.STANDBY
        );

        this.peerFlowSyncClient = new PeerFlowSyncClient(this.engineDirectoryPort, restClientBuilder.build());
    }

    @Test
    @DisplayName("syncStart는 알려진 피어의 내부 start 엔드포인트로 전달한다")
    void syncStartCallsPeerInternalEndpoint() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(this.peerB));
        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/start"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        this.peerFlowSyncClient.syncStart(FLOW_ID);

        this.restServiceServer.verify();
    }

    @Test
    @DisplayName("syncStop은 알려진 피어의 내부 stop 엔드포인트로 전달한다")
    void syncStopCallsPeerInternalEndpoint() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(this.peerB));
        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/stop"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        this.peerFlowSyncClient.syncStop(FLOW_ID);

        this.restServiceServer.verify();
    }

    @Test
    @DisplayName("syncRestart는 알려진 피어의 내부 restart 엔드포인트로 전달한다")
    void syncRestartCallsPeerInternalEndpoint() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(this.peerB));
        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/restart"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        this.peerFlowSyncClient.syncRestart(FLOW_ID);

        this.restServiceServer.verify();
    }

    @Test
    @DisplayName("syncReconfigure는 알려진 피어의 내부 config 엔드포인트로 body와 함께 전달한다")
    void syncReconfigureCallsPeerInternalEndpointWithBody() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(this.peerB));
        Map<String, Object> config = Map.of("threshold", 30);

        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/nodes/node-1/config"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"threshold\":30}"))
                .andRespond(withNoContent());

        this.peerFlowSyncClient.syncReconfigure(FLOW_ID, NODE_ID, config);

        this.restServiceServer.verify();
    }

    @Test
    @DisplayName("피어가 둘이면 각 피어 모두에게 전달한다")
    void syncStartCallsEveryKnownPeer() {
        EngineInfo peerC = new EngineInfo(
                "engine-c", "peer-host-2", 8083, 3, 0L, PresenceStatus.ONLINE, EngineRole.STANDBY
        );
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(this.peerB, peerC));

        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/start"))
                .andRespond(withNoContent());
        this.restServiceServer.expect(requestTo("http://peer-host-2:8083/api/v1/internal/flows/flow-1/start"))
                .andRespond(withNoContent());

        this.peerFlowSyncClient.syncStart(FLOW_ID);

        this.restServiceServer.verify();
    }

    @Test
    @DisplayName("피어 전달이 실패해도 예외를 던지지 않고 로그만 남긴다")
    void syncStartSwallowsFailureAndDoesNotThrow() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(this.peerB));
        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/start"))
                .andRespond(withServerError());

        assertThatCode(() -> this.peerFlowSyncClient.syncStart(FLOW_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("OFFLINE 피어에는 전달을 시도하지 않는다")
    void skipsOfflinePeer() {
        EngineInfo offlinePeer = new EngineInfo("engine-c", "dead-host", 8083, 3, 0L, PresenceStatus.OFFLINE, null);

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(offlinePeer));

        this.peerFlowSyncClient.syncStart(FLOW_ID);

        this.restServiceServer.verify(); // 기대한 요청을 하나도 등록하지 않았으므로, 요청이 나갔다면 실패함
    }

    @Test
    @DisplayName("AUTH_FAILED 피어에도 전달을 시도하지 않는다")
    void skipsAuthFailedPeer() {
        EngineInfo authFailedPeer = new EngineInfo("engine-c", "peer-host-2", 8083, 3, 0L, PresenceStatus.AUTH_FAILED, null);

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(authFailedPeer));

        this.peerFlowSyncClient.syncReconfigure(FLOW_ID, NODE_ID, Map.of("threshold", 30));

        this.restServiceServer.verify(); // 기대한 요청을 하나도 등록하지 않았으므로, 요청이 나갔다면 실패함
    }

    @Test
    @DisplayName("ONLINE 피어와 죽은 피어가 섞여 있으면 ONLINE 피어에만 전달한다")
    void sendsOnlyToOnlinePeers() {
        EngineInfo offlinePeer = new EngineInfo("engine-c", "dead-host", 8083, 3, 0L, PresenceStatus.OFFLINE, null);

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                this.peerB, offlinePeer
        ));

        this.restServiceServer.expect(requestTo("http://peer-host:8082/api/v1/internal/flows/flow-1/start"))
                .andRespond(withNoContent());

        this.peerFlowSyncClient.syncStart(FLOW_ID);

        this.restServiceServer.verify(); // engine-b로 간 요청 1건만 있어야 함
    }
}