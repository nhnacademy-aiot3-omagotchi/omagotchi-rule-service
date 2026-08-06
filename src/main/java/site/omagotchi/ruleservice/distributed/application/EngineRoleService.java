package site.omagotchi.ruleservice.distributed.application;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import site.omagotchi.ruleservice.distributed.application.port.EngineDirectoryPort;
import site.omagotchi.ruleservice.distributed.application.port.EnginePresenceListener;
import site.omagotchi.ruleservice.distributed.domain.EngineInfo;
import site.omagotchi.ruleservice.distributed.domain.EngineRole;
import site.omagotchi.ruleservice.distributed.domain.PresenceStatus;
import site.omagotchi.ruleservice.flow.application.FlowManager;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 정적 우선순위 규칙으로 ACTIVE/STANDBY 역할을 판정하고, Activatable 노드에 activate()/deactivate() 지시
 * 규칙: 나보다 우선순위가 높은(priority 값이 낮은) 피어가 하나라도 ONLINE이면 STANDBY, 아니면 ACTIVE
 * 기동 초기 대기(15s) 동안은 역할을 결정하지 않음 - 기동 순서와 무관하게 동일한 결과를 보장하기 위함
 */
@Service
@Slf4j
@ConditionalOnProperty(
        name = "eureka.client.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EngineRoleService implements EnginePresenceListener {

    private static final long INITIAL_WAIT_MS = 15_000L;

    private final EngineDirectoryPort engineDirectoryPort;
    private final EngineProperties engineProperties;
    private final FlowManager flowManager;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final long startedAt;

    // 첫 역할 결정 전까지는 널 - 이 동안 모든 Activatable 노드는 기본값(비활성) 유지
    @Getter
    private volatile EngineRole currentRole;

    public EngineRoleService(EngineDirectoryPort engineDirectoryPort,
                             EngineProperties engineProperties,
                             FlowManager flowManager,
                             @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") TaskScheduler taskScheduler, // IDE 오탐 이슈로 붙임.
                             Clock clock) {

        this.engineDirectoryPort = engineDirectoryPort;
        this.engineProperties = engineProperties;
        this.flowManager = flowManager;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.startedAt = this.clock.millis();
    }

    /**
     * 기동 초기 대기가 끝나는 시점에 첫 역할 판정을 강제로 한 번 실행
     * 그 전까지 피어 상태 변화가 없으면 onPresenceChanged()가 한 번도 호출되지 않을 수 있어서 별도로 예약함
     */
    @PostConstruct
    public void scheduleInitialEvaluation() {
        this.taskScheduler.schedule(
                this::reevaluate,
                Instant.now(this.clock).plusMillis(INITIAL_WAIT_MS)
        );
    }

    @Override
    public void onPresenceChanged() {
        this.reevaluate();
    }

    // package-private (테스트 클래스에서 @PostConstruct 스케줄링을 기다리지 않고 직접 호출해서 검증할 수 있도록)
    synchronized void reevaluate() {
        if (this.clock.millis() - this.startedAt < INITIAL_WAIT_MS) {
            log.debug("기동 초기 대기 중 - 역할 판정 보류");
            return;
        }

        EngineRole judged = this.judgeRole();

        if (judged == this.currentRole) {
            return; // 멱등성(실제 전환일 때만 게이트를 건드림)
        }

        log.info("역할 전환: {} -> {}", this.currentRole, judged);
        this.currentRole = judged;
        this.applyRole(judged);
    }

    private EngineRole judgeRole() {
        int myPriority = this.engineProperties.priority();
        String myId = this.engineProperties.id();

        boolean higherPriorityOnline = this.engineDirectoryPort.listEngines().stream()
                .filter(engineInfo -> engineInfo.presenceStatus() == PresenceStatus.ONLINE)
                .anyMatch(engineInfo -> isHigherPriority(engineInfo, myPriority, myId));

        return higherPriorityOnline
                ? EngineRole.STANDBY
                : EngineRole.ACTIVE;
    }

    private boolean isHigherPriority(EngineInfo otherEngineInfo, int myPriority, String myId) {
        if (otherEngineInfo.priority() != myPriority) {
            return otherEngineInfo.priority() < myPriority; // priority는 낮을수록 우선
        }

        // 우선순위 중복 - engineId 사전순이 빠른 쪽이 우선
        log.warn("[{}] 우선순위({}) 중복 - engineId 사전순으로", otherEngineInfo.engineId(), myPriority);
        return otherEngineInfo.engineId().compareTo(myId) < 0;
    }

    private void applyRole(EngineRole role) {
        List<Activatable> activatables = this.flowManager.getActivatableNodes();

        if (role == EngineRole.ACTIVE) {
            activatables.forEach(Activatable::activate);
        } else {
            activatables.forEach(Activatable::deactivate);
        }
    }
}
