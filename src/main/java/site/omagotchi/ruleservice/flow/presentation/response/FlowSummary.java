package site.omagotchi.ruleservice.flow.presentation.response;

import site.omagotchi.ruleservice.flow.domain.FlowState;

import java.util.List;

/**
 * 운영 API (GET /flows, GET /flows/{id}) 조회 응답용 요약 정보
 * 배포 시점의 구조(nodeIds)와 현재 실행 상태(status)를 한 번에 담음
 */
public record FlowSummary(
        String id,
        FlowState flowState,
        List<String> nodeIds
) {
}