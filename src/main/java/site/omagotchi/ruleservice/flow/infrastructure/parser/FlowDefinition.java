package site.omagotchi.ruleservice.flow.infrastructure.parser;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * @JsonIgnoreProperties(ignoreUnknown = true)가 클래스 레벨에 붙는 이유:
 * 알 수 없는 필드 -> 정의에 여분 필드가 있어도 파싱 성공, 전방 호환
 * 만약 이 어노테이션이 없으면, JSON에 NodeDefinition이 모르는 필드(예: 나중에 추가될 "version" 같은 것)가 하나라도 있으면 Jackson이 예외 던짐
 * 이 어노테이션을 붙이면 모르는 필드는 그냥 무시하고 넘어감 -> 스펙이 계속 진화해도 예전 파서가 새 필드가 섞인 JSON을 문제없이 읽을 수 있게 해줌
 *
 * id 중복 검증은 이 레코드를 감싸는 FlowParser.parse()가 담당
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowDefinition(
        String id,
        String name,
        String description,
        List<NodeDefinition> nodes, // 필수 (빈 배열이면 예외)
        List<ConnectionDefinition> connections // 선택 (없으면 빈 리스트)

        /*
        노드가 하나도 없는 플로우는 의미가 없음(빈 파이프라인)
        반면, 연결은 노드가 하나 뿐인 플로우(예: 트리거만 있고 아무 데도 안 보내는 노드)라면 없을 수도 있다고 보았음
        연결이 하나도 없는 플로우는 그 자체로 의미 없다고 판단된다면 connections도 필수로 바꿀 수 있음
         */
) {
    @JsonCreator
    public FlowDefinition(@JsonProperty("id") String id,
                          @JsonProperty("name") String name,
                          @JsonProperty("description") String description,
                          @JsonProperty("nodes") List<NodeDefinition> nodes,
                          @JsonProperty("connections") List<ConnectionDefinition> connections) {

        if(Objects.isNull(id) || id.isBlank()) {
            throw new IllegalArgumentException("id가 null이거나 비어있습니다.");
        }

        if(Objects.isNull(nodes) || nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes가 null이거나 비어있습니다.");
        }

        this.id = id;
        this.name = name;
        this.description = description;
        this.nodes = List.copyOf(nodes);
        this.connections = Objects.isNull(connections)
                ? List.of()
                : List.copyOf(connections);
    }
}