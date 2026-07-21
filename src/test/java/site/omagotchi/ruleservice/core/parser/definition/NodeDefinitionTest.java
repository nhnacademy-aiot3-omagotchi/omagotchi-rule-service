package site.omagotchi.ruleservice.core.parser.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeDefinitionTest {

    @Nested
    @DisplayName("정상 생성")
    class ValidConstruction {

        @Test
        @DisplayName("id, type, config가 모두 주어지면 정상 생성된다")
        void createsWithAllFields() {
            NodeDefinition nodeDefinition = new NodeDefinition("nodeA", "SampleSource", Map.of("threshold", 10));

            assertThat(nodeDefinition.id()).isEqualTo("nodeA");
            assertThat(nodeDefinition.type()).isEqualTo("SampleSource");
            assertThat(nodeDefinition.config()).containsEntry("threshold", 10);
        }

        @Test
        @DisplayName("config가 null이면 빈 Map으로 대체된다")
        void nullConfigBecomesEmptyMap() {
            NodeDefinition nodeDefinition = new NodeDefinition("nodeA", "SampleSource", null);

            assertThat(nodeDefinition.config()).isEmpty();
        }

        @Test
        @DisplayName("config는 원본과 독립적인 불변 복사본이다")
        void configIsDefensiveCopy() {
            Map<String, Object> mutableConfig = new java.util.HashMap<>();
            mutableConfig.put("key", "value");

            NodeDefinition nodeDefinition = new NodeDefinition("nodeA", "SampleSource", mutableConfig);
            mutableConfig.put("key", "changed");

            assertThat(nodeDefinition.config()).containsEntry("key", "value");
            assertThatThrownBy(() -> nodeDefinition.config().put("new", "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("null/빈 값 검증")
    class NullOrBlankValidation {

        @Test
        @DisplayName("id가 null이면 예외를 던진다")
        void nullIdThrowsException() {
            assertThatThrownBy(() -> new NodeDefinition(null, "SampleSource", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("id가 빈 문자열이면 예외를 던진다")
        void blankIdThrowsException() {
            assertThatThrownBy(() -> new NodeDefinition("  ", "SampleSource", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("type이 null이면 예외를 던진다")
        void nullTypeThrowsException() {
            assertThatThrownBy(() -> new NodeDefinition("nodeA", null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type");
        }

        @Test
        @DisplayName("type이 빈 문자열이면 예외를 던진다")
        void blankTypeThrowsException() {
            assertThatThrownBy(() -> new NodeDefinition("nodeA", "  ", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type");
        }
    }
}