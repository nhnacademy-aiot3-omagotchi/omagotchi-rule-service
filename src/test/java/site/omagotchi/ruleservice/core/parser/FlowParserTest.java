package site.omagotchi.ruleservice.core.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.core.parser.definition.FlowDefinition;

import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowParserTest {

    private FlowParser flowParser;

    @BeforeEach
    void setUp() {
        flowParser = new FlowParser(new ObjectMapper());
    }

    @Nested
    @DisplayName("정상 파싱")
    class ParseSuccess {

        @Test
        @DisplayName("노드와 연결이 모두 있는 유효한 플로우를 파싱한다")
        void parsesValidFlowWithNodesAndConnections() {
            String json = """
                    {
                      "id": "flow-1",
                      "name": "테스트 플로우",
                      "nodes": [
                        { "id": "nodeA", "type": "SampleSource", "config": {} },
                        { "id": "nodeB", "type": "SampleSink", "config": {} }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeB:in" }
                      ]
                    }
                    """;

            FlowDefinition result = flowParser.parse(json);

            assertThat(result.id()).isEqualTo("flow-1");
            assertThat(result.nodes()).hasSize(2);
            assertThat(result.connections()).hasSize(1);
        }

        @Test
        @DisplayName("connections가 없으면 빈 리스트로 파싱된다")
        void parsesFlowWithoutConnections() {
            String json = """
                    {
                      "id": "flow-2",
                      "nodes": [
                        { "id": "nodeA", "type": "SampleSource" }
                      ]
                    }
                    """;

            FlowDefinition result = flowParser.parse(json);

            assertThat(result.connections()).isEmpty();
        }

        @Test
        @DisplayName("알 수 없는 필드가 섞여 있어도 무시하고 파싱한다")
        void ignoresUnknownFields() {
            String json = """
                    {
                      "id": "flow-3",
                      "version": "v2",
                      "nodes": [
                        { "id": "nodeA", "type": "SampleSource" }
                      ]
                    }
                    """;

            FlowDefinition result = flowParser.parse(json);

            assertThat(result.id()).isEqualTo("flow-3");
        }
    }

    @Nested
    @DisplayName("JSON 파싱 실패")
    class ParseFailure {

        @Test
        @DisplayName("깨진 JSON은 IllegalArgumentException을 던진다")
        void malformedJsonThrowsIllegalArgumentException() {
            String json = "{ not valid json";

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("필수 필드(id) 누락 시 IllegalArgumentException 원인 메시지를 포함한 예외를 던진다")
        void missingRequiredFieldThrowsWithRootCauseMessage() {
            String json = """
                    {
                      "nodes": [
                        { "id": "nodeA", "type": "SampleSource" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("필수 필드");
        }

        @Test
        @DisplayName("nodes가 빈 배열이면 예외를 던진다")
        void emptyNodesThrowsException() {
            String json = """
                    {
                      "id": "flow-empty",
                      "nodes": []
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("검증: 중복 노드 ID")
    class DuplicateNodeIdValidation {

        @Test
        @DisplayName("동일한 id를 가진 노드가 두 개면 예외를 던진다")
        void duplicateNodeIdThrowsException() {
            String json = """
                    {
                      "id": "flow-dup",
                      "nodes": [
                        { "id": "nodeA", "type": "SampleSource" },
                        { "id": "nodeA", "type": "SampleSink" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("중복된 노드 ID")
                    .hasMessageContaining("nodeA");
        }
    }

    @Nested
    @DisplayName("검증: 연결의 노드 참조 무결성")
    class ConnectionReferenceValidation {

        @Test
        @DisplayName("존재하지 않는 소스 노드를 참조하면 예외를 던진다")
        void connectionReferencingUnknownSourceNodeThrowsException() {
            String json = """
                    {
                      "id": "flow-ref",
                      "nodes": [
                        { "id": "nodeB", "type": "SampleSink" }
                      ],
                      "connections": [
                        { "from": "ghost:out", "to": "nodeB:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("존재하지 않는 소스 노드")
                    .hasMessageContaining("ghost");
        }

        @Test
        @DisplayName("존재하지 않는 타겟 노드를 참조하면 예외를 던진다")
        void connectionReferencingUnknownTargetNodeThrowsException() {
            String json = """
                    {
                      "id": "flow-ref-2",
                      "nodes": [
                        { "id": "nodeA", "type": "SampleSource" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "ghost:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("존재하지 않는 대상 노드")
                    .hasMessageContaining("ghost");
        }
    }

    @Nested
    @DisplayName("검증: 순환 참조")
    class CycleValidation {

        @Test
        @DisplayName("직접 순환(A->B->A)이면 예외를 던진다")
        void directCycleThrowsException() {
            String json = """
                    {
                      "id": "flow-cycle",
                      "nodes": [
                        { "id": "nodeA", "type": "T" },
                        { "id": "nodeB", "type": "T" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeB:in" },
                        { "from": "nodeB:out", "to": "nodeA:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("순환 참조");
        }

        @Test
        @DisplayName("간접 순환(A->B->C->A)이면 예외를 던진다")
        void indirectCycleThrowsException() {
            String json = """
                    {
                      "id": "flow-cycle-2",
                      "nodes": [
                        { "id": "nodeA", "type": "T" },
                        { "id": "nodeB", "type": "T" },
                        { "id": "nodeC", "type": "T" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeB:in" },
                        { "from": "nodeB:out", "to": "nodeC:in" },
                        { "from": "nodeC:out", "to": "nodeA:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("순환 참조");
        }

        @Test
        @DisplayName("자기 자신을 향한 순환(A->A)이면 예외를 던진다")
        void selfLoopThrowsException() {
            String json = """
                    {
                      "id": "flow-self-loop",
                      "nodes": [
                        { "id": "nodeA", "type": "T" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeA:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("순환 참조");
        }

        @Test
        @DisplayName("순환이 없는 DAG는 정상 파싱된다 (다이아몬드 형태)")
        void diamondShapedDagParsesSuccessfully() {
            // A -> B -> D, A -> C -> D (순환처럼 보일 수 있는 다이아몬드 구조지만 순환 아님)
            String json = """
                    {
                      "id": "flow-diamond",
                      "nodes": [
                        { "id": "nodeA", "type": "T" },
                        { "id": "nodeB", "type": "T" },
                        { "id": "nodeC", "type": "T" },
                        { "id": "nodeD", "type": "T" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeB:in" },
                        { "from": "nodeA:out", "to": "nodeC:in" },
                        { "from": "nodeB:out", "to": "nodeD:in" },
                        { "from": "nodeC:out", "to": "nodeD:in" }
                      ]
                    }
                    """;

            FlowDefinition result = flowParser.parse(json);

            assertThat(result.nodes()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("검증 순서 고정 확인")
    class ValidationOrder {

        @Test
        @DisplayName("중복ID와 순환참조가 동시에 있으면 중복ID 에러가 먼저 발생한다")
        void duplicateIdErrorTakesPrecedenceOverCycle() {
            String json = """
                    {
                      "id": "flow-order",
                      "nodes": [
                        { "id": "nodeA", "type": "T" },
                        { "id": "nodeA", "type": "T" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeA:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("중복된 노드 ID");
        }

        @Test
        @DisplayName("존재하지않는노드참조와 순환참조가 동시에 있으면 참조 에러가 먼저 발생한다")
        void referenceErrorTakesPrecedenceOverCycle() {
            String json = """
                    {
                      "id": "flow-order-2",
                      "nodes": [
                        { "id": "nodeA", "type": "T" },
                        { "id": "nodeB", "type": "T" }
                      ],
                      "connections": [
                        { "from": "nodeA:out", "to": "nodeB:in" },
                        { "from": "nodeB:out", "to": "nodeA:in" },
                        { "from": "nodeA:out", "to": "ghost:in" }
                      ]
                    }
                    """;

            assertThatThrownBy(() -> flowParser.parse(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("존재하지 않는");
        }
    }

    @Nested
    @DisplayName("Path 기반 parse")
    class ParseFromPath {

        @Test
        @DisplayName("존재하지 않는 경로는 UncheckedIOException을 던진다")
        void nonExistentPathThrowsUncheckedIOException() {
            Path path = Path.of("/no/such/file.json");

            assertThatThrownBy(() -> flowParser.parse(path))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("읽을 수 없습니다");
        }
    }
}