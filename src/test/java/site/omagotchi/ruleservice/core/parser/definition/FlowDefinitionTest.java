package site.omagotchi.ruleservice.core.parser.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowDefinitionTest {

    private NodeDefinition sampleNode(String id) {
        return new NodeDefinition(id, "SampleSource", null);
    }

    @Nested
    @DisplayName("정상 생성")
    class ValidConstruction {

        @Test
        @DisplayName("id와 nodes가 있으면 정상 생성된다")
        void createsWithRequiredFields() {
            FlowDefinition flowDefinition = new FlowDefinition(
                    "flow-1", "테스트", "설명",
                    List.of(sampleNode("nodeA")), null
            );

            assertThat(flowDefinition.id()).isEqualTo("flow-1");
            assertThat(flowDefinition.nodes()).hasSize(1);
        }

        @Test
        @DisplayName("connections가 null이면 빈 List로 대체된다")
        void nullConnectionsBecomesEmptyList() {
            FlowDefinition flowDefinition = new FlowDefinition(
                    "flow-1", null, null,
                    List.of(sampleNode("nodeA")), null
            );

            assertThat(flowDefinition.connections()).isEmpty();
        }

        @Test
        @DisplayName("nodes와 connections는 원본과 독립적인 불변 복사본이다")
        void nodesAndConnectionsAreDefensiveCopies() {
            List<NodeDefinition> mutableNodes = new java.util.ArrayList<>();
            mutableNodes.add(sampleNode("nodeA"));

            FlowDefinition flowDefinition = new FlowDefinition("flow-1", null, null, mutableNodes, null);
            mutableNodes.add(sampleNode("nodeB"));

            assertThat(flowDefinition.nodes()).hasSize(1);
            assertThatThrownBy(() -> flowDefinition.nodes().add(sampleNode("nodeC")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("null/빈 값 검증")
    class NullOrBlankValidation {

        @Test
        @DisplayName("id가 null이면 예외를 던진다")
        void nullIdThrowsException() {
            assertThatThrownBy(() -> new FlowDefinition(null, null, null, List.of(sampleNode("nodeA")), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("id가 빈 문자열이면 예외를 던진다")
        void blankIdThrowsException() {
            assertThatThrownBy(() -> new FlowDefinition("  ", null, null, List.of(sampleNode("nodeA")), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("nodes가 null이면 예외를 던진다")
        void nullNodesThrowsException() {
            assertThatThrownBy(() -> new FlowDefinition("flow-1", null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nodes");
        }

        @Test
        @DisplayName("nodes가 빈 리스트면 예외를 던진다")
        void emptyNodesThrowsException() {
            assertThatThrownBy(() -> new FlowDefinition("flow-1", null, null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nodes");
        }
    }
}