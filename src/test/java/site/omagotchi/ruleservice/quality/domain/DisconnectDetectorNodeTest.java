package site.omagotchi.ruleservice.quality.domain;

import site.omagotchi.ruleservice.distributed.application.MutableClock;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.quality.infrastructure.QualityProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DisconnectDetectorNodeTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private LastSeenRegistry registry;
    private DisconnectDetectorNode node;
    private RecordingConnection disconnect;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        registry = new LastSeenRegistry();
        clock = new MutableClock(T0);

        QualityProperties properties = new QualityProperties(
                Map.of(), List.of(new QualityProperties.SensorId("eui-1", "temperature", 60))
        );

        node = new DisconnectDetectorNode("disconnect", registry, properties, clock);
        disconnect = new RecordingConnection();
        node.getOutputPort("disconnect").connect(disconnect);

        node.initialize();
        node.activate(); // Activatable 적용 후 - 실제 검사가 시작되려면 필요
    }

    @AfterEach
    void tearDown() {
        node.shutdown();
    }

    @Test
    @DisplayName("기동 직후 보류 기간에는 끊김으로 판정하지 않는다")
    void noFalsePositiveRightAfterStartTest() {
        node.check();

        assertThat(disconnect.messages()).isEmpty();
    }

    @Test
    @DisplayName("임계를 넘게 소식이 없으면 끊김 시작을 발행한다")
    void disconnectStartTest() {
        registry.update("eui-1", "temperature", Instant.now(clock)); // 활성화 직후 수신
        clock.advance(Duration.ofSeconds(300)); // 임계(180초) 초과

        node.check();

        assertThat(disconnect.messages()).hasSize(1);
        QualityEvent qualityEvent = disconnect.messages().getFirst().get("qualityEvent");
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.DISCONNECTED);
        assertThat(qualityEvent.deviceEui()).isEqualTo("eui-1");
        assertThat(qualityEvent.detail()).isEqualTo("끊김 시작");
    }

    @Test
    @DisplayName("끊김 상태가 이어져도 신고를 반복하지 않는다")
    void noRepeatedAlertTest() {
        registry.update("eui-1", "temperature", Instant.now(clock));
        clock.advance(Duration.ofSeconds(300)); // 임계(180초) 초과

        node.check();
        node.check();
        node.check();

        assertThat(disconnect.messages()).hasSize(1);
    }

    @Test
    @DisplayName("다시 수신되면 끊김 종료를 발행한다")
    void disconnectEndTest() {
        registry.update("eui-1", "temperature", Instant.now(clock));
        clock.advance(Duration.ofSeconds(300)); // 임계(180초) 초과
        node.check(); // 끊김 시작

        registry.update("eui-1", "temperature", Instant.now(clock)); // 다시 수신
        node.check();

        assertThat(disconnect.messages()).hasSize(2);
        QualityEvent qualityEvent = disconnect.messages().get(1).get("qualityEvent");
        assertThat(qualityEvent.detail()).isEqualTo("끊김 종료");
    }

    @Test
    @DisplayName("센서마다 설정된 주기에 따라 임계가 다르게 적용된다")
    void perSensorThresholdTest() {
        QualityProperties properties = new QualityProperties(Map.of(), List.of(
                new QualityProperties.SensorId("fast", "temperature", 5),   // 임계 15초
                new QualityProperties.SensorId("slow", "temperature", 900)  // 임계 2700초
        ));
        DisconnectDetectorNode node2 = new DisconnectDetectorNode("d2", registry, properties, clock);
        RecordingConnection disconnect2 = new RecordingConnection();
        node2.getOutputPort("disconnect").connect(disconnect2);
        node2.initialize();
        node2.activate();

        registry.update("fast", "temperature", Instant.now(clock));
        registry.update("slow", "temperature", Instant.now(clock));

        clock.advance(Duration.ofSeconds(20)); // fast만 임계 초과
        node2.check();

        assertThat(disconnect2.messages()).hasSize(1);
        QualityEvent event = disconnect2.messages().get(0).get("qualityEvent");
        assertThat(event.deviceEui()).isEqualTo("fast");

        node2.shutdown();
    }

    @Test
    @DisplayName("deactivate 후 다시 activate 하면 결측 상태가 초기화되어 다시 신고한다")
    void resetsStateAfterDeactivateThenReactivate() {
        this.registry.update("eui-1", "temperature", Instant.now(this.clock));
        this.clock.advance(Duration.ofSeconds(300));

        this.node.check(); // 첫 결측 신고
        assertThat(this.disconnect.messages()).hasSize(1);

        this.node.deactivate(); // STANDBY 전환 (disconnectKeys 초기화)
        this.node.activate(); // 다시 ACTIVE 전환 (startedAt도 리셋)

        this.clock.advance(Duration.ofSeconds(300)); // 재활성화 이후로도 임계를 넘김

        this.node.check(); // 내부 상태가 리셋됐으니 다시 신고해야 함

        assertThat(this.disconnect.messages()).hasSize(2);
    }

    @Test
    @DisplayName("activate 전이거나 이미 deactivate된 상태에서 deactivate를 호출해도 예외 안 던진다")
    void deactivateIsSafeWhenNotActivated() {
        DisconnectDetectorNode freshNode = new DisconnectDetectorNode("fresh", this.registry, new QualityProperties(Map.of(), List.of()), this.clock);
        freshNode.initialize();

        assertThatCode(() -> {
            freshNode.deactivate(); // activate 호출 전
            freshNode.deactivate(); // 이미 deactivate된 상태에서 한 번 더
        }).doesNotThrowAnyException();

        freshNode.shutdown();
    }

    /**
     * 이 테스트는 실제로 6초를 기다리는 테스트라서 느림
     * CHECK_INTERNAL(현재 5초, private 상수)가 나중에 바뀌면 이 테스트의 대기 시간도 같이 조정해야 함 (기억할 것)
     */
    @Test
    @DisplayName("initialize()만 호출하고 activate()를 안 하면, 백그라운드 검사 타이머가 몰래 돌지 않는다")
    void doesNotRunBackgroundCheckBeforeActivate() throws InterruptedException {
        // 임계값을 짧게(1초 * 3배 = 3초) 잡아서, 백그라운드 타이머가 몰래 돌고 있었다면
        // CHECK_INTERVAL(5초) 첫 틱에서 바로 결측으로 오판정해 이벤트를 발행했을 것
        QualityProperties properties = new QualityProperties(
                Map.of(), List.of(new QualityProperties.SensorId("eui-2", "temperature", 1))
        );
        DisconnectDetectorNode freshNode = new DisconnectDetectorNode("fresh-check", this.registry, properties, this.clock);
        RecordingConnection freshDisconnect = new RecordingConnection();
        freshNode.getOutputPort("disconnect").connect(freshDisconnect);

        freshNode.initialize(); // activate()는 호출 안 함 - STANDBY 상태를 흉내냄

        Thread.sleep(6_000); // CHECK_INTERVAL(5초)이 최소 한 번은 돌 수 있는 시간만큼 대기

        assertThat(freshDisconnect.messages()).isEmpty();

        freshNode.shutdown();
    }

    /**
     * 이 테스트는 최대 10초까지 기다릴 수 있는 느린 테스트
     * 첫 실행이 정확히 CHECK_INTERVAL(5초) 뒤라는 보장이 없어(스케줄링 지연) 고정 sleep 대신 CountDownLatch로 대기
     */
    @Test
    @DisplayName("shutdown 후 재기동(initialize -> activate)하면 결측 감지 타이머가 다시 걸린다")
    void reschedulesCheckTimerAfterShutdownThenReinitialize() throws InterruptedException {
        QualityProperties properties = new QualityProperties(
                Map.of(), List.of(new QualityProperties.SensorId("eui-3", "temperature", 1))
        );
        DisconnectDetectorNode freshNode = new DisconnectDetectorNode("fresh-restart", this.registry, properties, Clock.systemUTC());

        CountDownLatch firstEventArrived = new CountDownLatch(1);
        RecordingConnection freshDisconnect = new RecordingConnection() {
            @Override
            public void deliver(Message message) throws InterruptedException {
                super.deliver(message);
                firstEventArrived.countDown();
            }
        };
        freshNode.getOutputPort("disconnect").connect(freshDisconnect);

        try {
            freshNode.initialize();
            freshNode.activate(); // 최초 기동 - 타이머 등록

            freshNode.shutdown(); // FlowEngine.stop()에 해당 - 버그가 있으면 checkTask가 낡은 채로 남음
            freshNode.initialize(); // FlowEngine.start()에 해당 - 새 executor 생성
            freshNode.activate(); // 재승격 - 버그가 있으면 checkTask != null이라 여기서 조용히 아무 일도 안 함

            boolean arrived = firstEventArrived.await(10, TimeUnit.SECONDS); // CHECK_INTERVAL(5초) + 여유
            assertThat(arrived).isTrue();

            assertThat(freshDisconnect.messages()).hasSize(1);
            QualityEvent qualityEvent = freshDisconnect.messages().getFirst().get("qualityEvent");
            assertThat(qualityEvent.detail()).isEqualTo("끊김 시작");
        } finally {
            freshNode.shutdown();
        }
    }
}
