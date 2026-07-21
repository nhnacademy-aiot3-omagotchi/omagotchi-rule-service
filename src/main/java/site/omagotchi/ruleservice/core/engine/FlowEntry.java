package site.omagotchi.ruleservice.core.engine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import site.omagotchi.ruleservice.core.parser.definition.FlowDefinition;

/**
 * FlowManager가 "이 플로우가 어떤 정의로 배포됐는지"를 기억해두는 용도
 * 원본 정의(JSON에서 온 것)를 보관
 */
@RequiredArgsConstructor
@Getter
class FlowEntry {

    private final FlowDefinition flowDefinition;
}