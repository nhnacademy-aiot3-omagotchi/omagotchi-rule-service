package site.omagotchi.ruleservice.flow.application;

import site.omagotchi.ruleservice.flow.infrastructure.parser.FlowDefinition;

/**
 * FlowManager가 "이 플로우가 어떤 정의로 배포됐는지"를 기억해두는 용도
 * 원본 정의(JSON에서 온 것)를 보관
 */
record FlowEntry(
        FlowDefinition flowDefinition
) {
}