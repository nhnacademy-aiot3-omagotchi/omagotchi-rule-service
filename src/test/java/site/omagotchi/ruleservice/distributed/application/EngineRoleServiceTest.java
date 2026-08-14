package site.omagotchi.ruleservice.distributed.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.flow.application.FlowManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EngineRoleServiceTest {

    private static final long INITIAL_WAIT_MS = 15_000L;

    private EngineDirectoryPort engineDirectoryPort;
    private EngineProperties engineProperties;
    private FlowManager flowManager;
    private TaskScheduler taskScheduler;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        this.engineDirectoryPort = mock(EngineDirectoryPort.class);
        this.engineProperties = new EngineProperties("engine-a", 1);
        this.flowManager = mock(FlowManager.class);
        this.taskScheduler = mock(TaskScheduler.class);
        this.clock = new MutableClock(Instant.now());

        // 기본값: 활성화 항상 성공 - 재시도 자체를 테스트하는 케이스만 개별적으로 false로 덮어쓰기
        lenient().when(this.flowManager.applyActivationState(anyBoolean())).thenReturn(true);
    }

    private EngineRoleService newService() {
        return new EngineRoleService(
                this.engineDirectoryPort,
                this.engineProperties,
                this.flowManager,
                this.taskScheduler,
                this.clock
        );
    }

    @Test
    @DisplayName("초기 대기 중에는 역할을 판정하지 않는다")
    void doNotEvaluateWhenInitWait() {
        EngineRoleService engineRoleService = this.newService();

        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS - 1));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isNull();
        verify(this.flowManager, never()).applyActivationState(anyBoolean());
    }

    @Test
    @DisplayName("자신보다 우선순위가 높은 피어가 없으면 ACTIVE로 판정한다")
    void higherPriorityThenSelfEvaluateActive() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", 2, PresenceStatus.ONLINE) // priority 2 -> 나(1)보다 낮은 우선순위
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        verify(this.flowManager).applyActivationState(true);
    }

    @Test
    @DisplayName("자신보다 우선순위가 높은 피어가 ONLINE이면 STANDBY로 판정한다")
    void higherPriorityIsOnlineThenEvaluateStandby() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", 0, PresenceStatus.ONLINE) // priority 0 - 나(1)보다 높은 우선순위
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.flowManager).applyActivationState(false);
    }

    @Test
    @DisplayName("우선순위 높은 피어가 OFFLINE이면 판정에서 제외된다")
    void higherPriorityOfflineThenExcludedOnEvaluation() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", 0, PresenceStatus.OFFLINE) // priority 0으로 나보다 높지만, OFFLINE이므로 무시되어야 함
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
    }

    @Test
    @DisplayName("같은 역할로 재판정되면 activate, deactivate를 다시 호출하지 않는다")
    void reevaluatedToSameRoleThenDoNotCallActivateOrDeactivate() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());

        EngineRoleService engineRoleService = newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));

        engineRoleService.reevaluate(); // 첫 판정 - ACTIVE
        engineRoleService.reevaluate(); // 재판정 - 여전히 ACTIVE (멱등성)

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        verify(this.flowManager, times(1)).applyActivationState(true); // 두 번 호출되면 안 됨.
    }

    @Test
    @DisplayName("우선순위가 같으면 engineId 사전순이 빠른 쪽이 ACTIVE로 판정된다")
    void samePriorityTiesBrokenByEngineIdLexicographicOrder() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-z", 1, PresenceStatus.ONLINE) // 같은 priority(1), engineId만 "engine-a"보다 사전순으로 위
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // "engine-a" < "engine-b" -> a가 우선
    }

    @Test
    @DisplayName("우선순위가 같고 상대 engineId가 사전순으로 앞서면 STANDBY로 판정된다")
    void samePriorityLosesToLexicographicallyEarlierPeer() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE) // 같은 priority(1), "engine-a"보다 사전순으로 앞섬
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
    }

    @Test
    @DisplayName("이미 STANDBY인 상태에서 상위 피어가 사라져도 즉시 전환하지 않고, grace 경과 후에도 여전히 없으면 ACTIVE로 전환한다")
    void gracePeriodDelaysFailoverAndAppliesIfStillGoneAfterGrace() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE)
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 최초 배정 - 스탠바이

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of()); // 상위 피어 사라짐

        engineRoleService.reevaluate(); // failover 후보 - grace 예약

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY); // 아직 안 바뀜
        verify(this.flowManager, never()).applyActivationState(true);

        this.clock.advance(Duration.ofMillis(5_000L));
        this.runLastScheduledTask(); // confirmFailover() 실행 - 재계산해도 여전히 없음

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        verify(this.flowManager).applyActivationState(true);
    }

    @Test
    @DisplayName("grace 대기 중 상위 피어가 복귀하면 failover가 취소된다")
    void gracePeriodCancelledWhenPeerReturnsBeforeGraceElapses() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE)
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 스탠바이

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());
        engineRoleService.reevaluate(); // failover 후보 - grace 예약

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE)
        )); // grace 도중 복귀

        this.clock.advance(Duration.ofMillis(5_000L));
        this.runLastScheduledTask(); // confirmFailover() 재계산 시점엔 이미 복귀함 -> 스탠바이 유지

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.flowManager, never()).applyActivationState(true);
    }

    @Test
    @DisplayName("이미 ACTIVE인 상태에서 상위 피어가 복귀해도 1번만으로는 전환하지 않고, 연속 2번째 확인에 STANDBY로 전환한다")
    void fallbackHysteresisRequiresTwoConsecutiveConfirmations() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 최초 배정 - 액티브

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE)
        )); // 상위 피어 복귀 - 1번째 확인
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // 아직 안 바뀜
        verify(this.flowManager, never()).applyActivationState(false);

        this.clock.advance(Duration.ofMillis(5_000));
        this.runLastScheduledTask(); // 2번째 확인

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.flowManager).applyActivationState(false);
    }

    @Test
    @DisplayName("failback 히스테리시스 확인 도중 상위 피어가 다시 사라지면 카운터가 리셋된다")
    void failbackHysteresisResetsWhenPeerDisappearsAgain() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 액티브 (현재 내가 최상위)

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE) // 상위 피어 등장
        ));
        engineRoleService.reevaluate(); // 1번째 확인

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of()); // 다시 사라짐

        engineRoleService.reevaluate(); // judged == currentRole(ACTIVE) -> 카운터 리셋

        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 1, PresenceStatus.ONLINE) // 다시 상위 피어 등장
        ));
        engineRoleService.reevaluate(); // 리셋됐으므로 다시 1번째 확인일 뿐

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // 아직 전환 안 됨
    }

    @Test
    @DisplayName("최초 판정 때 상위 우선순위 피어가 AUTH_FAILED면 승격하지 않고 안전하게 STANDBY로 판정한다")
    void higherPriorityAuthFailedThenEvaluateStandbyOnInitialAssignment() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 0, PresenceStatus.AUTH_FAILED) // priority 0 -> 나(1)보다 높은 우선순위인데 인증 실패 상태
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.flowManager, never()).applyActivationState(true);
    }

    @Test
    @DisplayName("이미 STANDBY인 상태에서 상위 피어가 AUTH_FAILED로 바뀌어도 승격을 시도하지 않고 STANDBY를 유지한다")
    void higherPriorityAuthFailedDoesNotTriggerPromotionWhenAlreadyStandby() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 0, PresenceStatus.ONLINE)
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 최초 배정 - 스탠바이

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);

        // 상위 피어의 시크릿이 어긋나서 AUTH_FAILED로 전환 (예: 시크릿 로테이션 중 한쪽만 갱신)
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 0, PresenceStatus.AUTH_FAILED)
        ));
        engineRoleService.reevaluate();

        // OFFLINE이었다면 여기서 grace 스케줄이 걸렸어야 하는데, AUTH_FAILED는 승격 후보로도 안 잡힘
        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.flowManager, never()).applyActivationState(true);
    }

    @Test
    @DisplayName("최초 판정 때 낮은 우선순위 피어가 이미 ONLINE+ACTIVE로 활동 중이면, 곧바로 뺏지 않고 STANDBY로 시작한다")
    void initialAssignmentStartsAsStandbyWhenLowerPriorityPeerAlreadyActive() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", 2, PresenceStatus.ONLINE, EngineRole.ACTIVE) // priority 2 -> 나(1)보다 낮지만 이미 ACTIVE
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);
        verify(this.flowManager, never()).applyActivationState(true);
    }

    @Test
    @DisplayName("피어가 ACTIVE를 보고했어도 OFFLINE이면(죽은 뒤 남은 옛 role 값) 무시하고 ACTIVE로 판정한다")
    void initialAssignmentBecomesActiveWhenReportedActivePeerIsOffline() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-b", 2, PresenceStatus.OFFLINE, EngineRole.ACTIVE) // 죽기 전엔 ACTIVE였지만 지금은 OFFLINE
        ));

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate();

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);
        verify(this.flowManager).applyActivationState(true);
    }

    @Test
    @DisplayName("역할 적용이 일부 실패하면 일정 시간 뒤 재시도한다")
    void retriesApplyRoleWhenActivationPartiallyFails() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());
        when(this.flowManager.applyActivationState(true))
                .thenReturn(false) // 최초 시도 - 일부 실패
                .thenReturn(true); // 재시도 - 성공

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 최초 배정 - ACTIVE, 그런데 적용 실패

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // currentRole 자체는 이미 커밋됨
        verify(this.flowManager, times(1)).applyActivationState(true);

        this.clock.advance(Duration.ofMillis(3_000L));
        this.runLastScheduledTask(); // 예약된 재시도 실행

        verify(this.flowManager, times(2)).applyActivationState(true); // 재시도로 한 번 더 호출됨
    }

    @Test
    @DisplayName("재시도 예약 뒤 역할이 또 바뀌면, 예약됐던 재시도는 낡은 것으로 보고 건너뛴다")
    void skipsStaleRetryWhenRoleChangedBeforeRetryFires() {
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of(
                peer("engine-0", 0, PresenceStatus.ONLINE) // 상위 우선순위 피어 존재 - 최초 판정은 STANDBY
        ));
        when(this.flowManager.applyActivationState(false)).thenReturn(false); // STANDBY 적용 실패 -> 재시도 예약
        when(this.flowManager.applyActivationState(true)).thenReturn(true); // 나중에 ACTIVE로 바뀔 땐 성공

        EngineRoleService engineRoleService = this.newService();
        this.clock.advance(Duration.ofMillis(INITIAL_WAIT_MS));
        engineRoleService.reevaluate(); // 최초 배정 - STANDBY, 적용 실패 -> 3초 뒤 재시도 예약(1)

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.STANDBY);

        // 상위 피어가 사라짐 - failover 후보로 감지, grace(1.5초) 뒤 재확인 예약(2)
        when(this.engineDirectoryPort.listEngines()).thenReturn(List.of());
        engineRoleService.reevaluate();

        this.clock.advance(Duration.ofMillis(1_500L));

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(this.taskScheduler, atLeast(2)).schedule(captor.capture(), any(Instant.class));
        List<Runnable> scheduled = captor.getAllValues();
        Runnable staleStandbyRetry = scheduled.get(0); // 맨 처음 예약된 것 = STANDBY 재시도
        Runnable confirmFailover = scheduled.get(1); // 두 번째 예약된 것 = failover 재확인

        confirmFailover.run(); // 실제로 ACTIVE로 전환됨

        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE);

        staleStandbyRetry.run(); // 낡은 STANDBY 재시도가 뒤늦게 실행됨

        // STANDBY로는 재적용 안 됐어야 함 (최초 1번만 호출된 채로 유지)
        verify(this.flowManager, times(1)).applyActivationState(false);
        assertThat(engineRoleService.getCurrentRole()).isEqualTo(EngineRole.ACTIVE); // 여전히 ACTIVE 유지
    }

    /**
     * 가장 최근에 taskScheduler.schedule(...)로 예약된 작업을 직접 실행 (grace/히스테리시스 재확인 시뮬레이션)
     */
    private void runLastScheduledTask() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);

        verify(this.taskScheduler, atLeastOnce())
                .schedule(captor.capture(), any(Instant.class));

        captor.getValue().run();
    }

    private static EngineInfo peer(String engineId, int priority, PresenceStatus presenceStatus) {
        return peer(engineId, priority, presenceStatus, null);
    }

    private static EngineInfo peer(String engineId, int priority, PresenceStatus presenceStatus, EngineRole engineRole) {
        return new EngineInfo(
                engineId,
                "localhost",
                8080,
                priority,
                0L,
                presenceStatus,
                engineRole);
    }
}
