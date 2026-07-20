package site.omagotchi.ruleservice.core.engine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import site.omagotchi.ruleservice.core.flow.Flow;

import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor
@Getter
class FlowExecution {

    private final Flow flow;

    @Setter
    private List<Thread> workerThreads = Collections.emptyList();

    @Setter
    private FlowState flowState = FlowState.STOPPED;
}