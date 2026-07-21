package site.omagotchi.ruleservice.core.parser.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

public record NodeDefinition(
        String id,
        String type,
        Map<String, Object> config // config 생략 시 빈 Map
) {
    /**
     * 컴팩트 생성자 말고 @JsonCreator가 붙은 전체 생성자를 쓴 이유:
     * config가 null이면 빈 Map으로 바꿔줘야 하기 때문
     * Jackson이 JSON을 파싱할 때 이 생성자를 쓰도록 @JsonCreator로 표시함
     */
    @JsonCreator
    public NodeDefinition(@JsonProperty("id") String id,
                          @JsonProperty("type") String type,
                          @JsonProperty("config") Map<String, Object> config) {

        if (Objects.isNull(id) || id.isBlank()) {
            throw new IllegalArgumentException("id가 null이거나 비어있습니다.");
        }

        if (Objects.isNull(type) || type.isBlank()) {
            throw new IllegalArgumentException("type이 null이거나 비어있습니다.");
        }

        this.id = id;
        this.type = type;
        this.config = Objects.isNull(config)
                ? Map.of()
                : Map.copyOf(config);
    }
}