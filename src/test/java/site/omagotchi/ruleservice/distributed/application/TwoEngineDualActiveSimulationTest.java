package site.omagotchi.ruleservice.distributed.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.flow.application.FlowManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static site.omagotchi.ruleservice.distributed.application.EngineRoleService.GRACE_MS;
import static site.omagotchi.ruleservice.distributed.application.EngineRoleService.INITIAL_WAIT_MS;

/**
 * EngineRoleService 두 인스턴스를 서로 바라보는 EngineDirectoryPort로 묶고 MutableClock/TaskScheduler를 공유시켜서,
 * grace·히스테리시스·자가 치유가 실제 타이밍 조합에서도 정확히 하나의 ACTIVE로 수렴하는지 검증
 * 폴링/탐지 자체(EngineDiscoveryService의 책임)는 시뮬레이션하지 않음
 * - "상대 상태가 바뀐 걸 감지했다"는 신호(onPresenceChanged)는 테스트가 실제 운영 순서에 맞춰 직접 흉내냄.
 * - 여기서 검증하려는 건 오직 EngineRoleService의 판정 로직이 두 인스턴스 사이에서 실제로 안전하게 수렴하는가임
 */
class TwoEngineDualActiveSimulationTest {

    private MutableClock clock;
    private SimulatedScheduler scheduler;
    private FlowManager flowManagerA;
    private FlowManager flowManagerB;
    private LiveMirrorPort directoryOfA; // A가 보는 피어(B)
    private LiveMirrorPort directoryOfB; // B가 보는 피어(A)
    private EngineRoleService engineA; // priority 1 (상위)
    private EngineRoleService engineB; // priority 2 (하위)

    @BeforeEach
    void setUp() {
        this.clock = new MutableClock(Instant.now());
        this.scheduler = new SimulatedScheduler();

        this.flowManagerA = mock(FlowManager.class);
        this.flowManagerB = mock(FlowManager.class);
        lenient().when(this.flowManagerA.applyActivationState(anyBoolean())).thenReturn(true);
        lenient().when(this.flowManagerB.applyActivationState(anyBoolean())).thenReturn(true);

        // 람다는 호출 시점에 필드를 읽으므로, 아래 engineA/engineB 대입이 끝난 뒤부터 정상 참조됨
        this.directoryOfA = new LiveMirrorPort("engine-b", 8082, 2, () -> this.engineB.getCurrentRole());
        this.directoryOfB = new LiveMirrorPort("engine-a", 8081, 1, () -> this.engineA.getCurrentRole());

        this.engineA = new EngineRoleService(this.directoryOfA, new EngineProperties("engine-a", 1, 1), this.flowManagerA, this.scheduler, this.clock);
        this.engineB = new EngineRoleService(this.directoryOfB, new EngineProperties("engine-b", 2, 1), this.flowManagerB, this.scheduler, this.clock);
    }

    @Test
    @DisplayName("두 엔진이 동시에 기동해도 정확히 하나만 ACTIVE로 수렴한다")
    void simultaneousColdStartConvergesToExactlyOneActive() {
        this.engineA.scheduleInitialEvaluation();
        this.engineB.scheduleInitialEvaluation();

        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(INITIAL_WAIT_MS));

        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
    }

    @Test
    @DisplayName("상위 엔진(A) 장애 -> B 승격 -> A 복구 -> reconcile로 우선순위 복원(안전망 경로 검증), 전 구간에서 정확히 하나만 ACTIVE로 수렴한다")
    void failoverThenFailbackConvergesToExactlyOneActive() {
        // 1. 정상 기동 - A=ACTIVE, B=STANDBY 확립
        this.engineA.scheduleInitialEvaluation();
        this.engineB.scheduleInitialEvaluation();
        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(INITIAL_WAIT_MS));
        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.STANDBY);

        // 2. A 장애 - B가 A를 OFFLINE으로 감지(실제로는 EngineDiscoveryService 폴링이 감지)
        this.directoryOfB.setPresenceStatus(PresenceStatus.OFFLINE);
        this.engineB.onPresenceChanged(); // failover 후보 - grace 예약됨

        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(GRACE_MS));
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // grace 경과 후 승격

        // 3. A 복구 - 재기동이므로 새 인스턴스(currentRole=null, 기동 시각도 지금부터 다시 15초)
        this.directoryOfB.setPresenceStatus(PresenceStatus.ONLINE);
        this.engineA = new EngineRoleService(this.directoryOfA, new EngineProperties("engine-a", 1, 1), this.flowManagerA, this.scheduler, this.clock);
        this.engineA.scheduleInitialEvaluation();
        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(INITIAL_WAIT_MS));

        // A는 최상위 우선순위지만, 이미 활동 중인 B를 곧바로 뺏지 않으려고 일단 STANDBY로 시작함
        // (최초 배정은 grace 없이 즉시 적용되므로, 여기서 안 막으면 B가 강등할 때까지 이중 ACTIVE 구간이 생김)
        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // 이 구간에도 ACTIVE는 정확히 하나

        // 4. reconcile이 재판정을 돌려 우선순위를 복원 - 실제로는 최초 배정 직후 예약되는 조기 재판정(COLD_BOOT_RECHECK_MS)이 주 경로이고,
        // 여기서는 그 예약과 무관하게 reconcile 자체가 안전망으로도 똑같이 복원해내는지 직접 검증
        this.engineA.reconcile();
        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(GRACE_MS)); // failover 후보 -> grace 경과 후 승격
        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);

        // 5. B가 A의 복귀(ONLINE+ACTIVE)를 감지 - 자가 치유 경로로 히스테리시스 없이 즉시 강등
        this.engineB.onPresenceChanged();

        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
    }

    // 한계를 증명하는 테스트
    @Test
    @DisplayName("한계 증명: A-B 내부 HTTP만 끊기고 각자 프로세스는 살아있는 비대칭 단절에서는, 자가 치유가 닿을 방법이 없어 둘 다 ACTIVE로 영구히 남는다")
    void asymmetricPartitionCanLeaveBothActivePermanently() {
        // 1. 정상 기동 - A=ACTIVE, B=STANDBY 확립
        this.engineA.scheduleInitialEvaluation();
        this.engineB.scheduleInitialEvaluation();
        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(INITIAL_WAIT_MS));
        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.STANDBY);

        // 2. A<->B 내부 HTTP만 단절 (MQTT 등 다른 경로는 살아있다고 가정 - 이 테스트의 시뮬레이션 대상 아님)
        // A는 애초에 자기가 최상위 우선순위라 B를 보고 역할을 정하지 않으므로, A 쪽은 아무것도 안 건드려도 그대로 ACTIVE
        this.directoryOfB.setPresenceStatus(PresenceStatus.OFFLINE); // B가 A에게 못 닿음
        this.engineB.onPresenceChanged(); // failover 후보 - grace 예약

        this.scheduler.advanceTo(this.clock, this.clock.instant().plusMillis(GRACE_MS));

        // B는 A가 안 보이니 정상적인 failover 절차를 밟아 승격 - 이 판단 자체는 올바름(A가 진짜 죽었을 수도 있으니 구분 불가)
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        // A는 실제로는 살아있고, 자기 판정에 B의 상태가 관여하지 않아 계속 ACTIVE
        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);

        // 3. 시간이 아무리 지나고 reconcile이 여러 번 돌아도 회복되지 않음
        // 자가 치유(higherPriorityPeerReportsActive)는 "상대를 다시 볼 수 있게 됨"이 전제인데, HTTP가 안 뚫리는 한 그 전제 자체가 성립하지 않음
        this.engineA.reconcile();
        this.engineB.reconcile();
        this.scheduler.advanceTo(this.clock, this.clock.instant().plus(Duration.ofMinutes(10)));

        assertThat(this.engineA.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        assertThat(this.engineB.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // 여전히 둘 다 ACTIVE - 이 설계의 알려진 한계
    }

    // 서로의 실제 currentRole을 실시간으로 비추는 가짜 EngineDirectoryPort
    private static class LiveMirrorPort implements EngineDirectoryPort {
        private final String peerEngineId;
        private final int peerPort;
        private final int peerPriority;
        private final Supplier<EngineRole> peerRoleSupplier;
        private volatile PresenceStatus presenceStatus = PresenceStatus.ONLINE;

        LiveMirrorPort(String peerEngineId, int peerPort, int peerPriority, Supplier<EngineRole> peerRoleSupplier) {
            this.peerEngineId = peerEngineId;
            this.peerPort = peerPort;
            this.peerPriority = peerPriority;
            this.peerRoleSupplier = peerRoleSupplier;
        }

        @Override
        public List<EngineInfo> listEngines() {
            return List.of(new EngineInfo(
                    this.peerEngineId, "peer-host", this.peerPort, this.peerPriority,
                    0L, this.presenceStatus, this.peerRoleSupplier.get()
            ));
        }

        void setPresenceStatus(PresenceStatus status) {
            this.presenceStatus = status;
        }
    }

    // 두 EngineRoleService가 공유하는 가짜 스케줄러 - 예약된 작업을 실행 시각 순서대로, clock을 그 시각까지 진행시키며 실행
    private static class SimulatedScheduler implements TaskScheduler {

        private record ScheduledTask(Runnable task, Instant when) {
        }

        private final List<ScheduledTask> pending = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            this.pending.add(new ScheduledTask(task, startTime));
            return null; // EngineRoleService는 반환값을 쓰지 않음
        }

        void advanceTo(MutableClock clock, Instant target) {
            while (true) {
                ScheduledTask next = this.pending.stream()
                        .min(Comparator.comparing(ScheduledTask::when))
                        .filter(t -> !t.when().isAfter(target))
                        .orElse(null);

                if (next == null) {
                    break;
                }

                this.pending.remove(next);

                Duration delta = Duration.between(clock.instant(), next.when());
                if (!delta.isNegative()) {
                    clock.advance(delta);
                }

                next.task().run();
            }

            Duration remaining = Duration.between(clock.instant(), target);
            if (!remaining.isNegative()) {
                clock.advance(remaining);
            }
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException("미사용");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw new UnsupportedOperationException("미사용");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException("미사용");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            throw new UnsupportedOperationException("미사용");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException("미사용");
        }
    }
}
