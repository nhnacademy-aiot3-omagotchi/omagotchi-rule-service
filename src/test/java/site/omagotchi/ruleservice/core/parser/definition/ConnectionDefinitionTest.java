package site.omagotchi.ruleservice.core.parser.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionDefinitionTest {

    @Nested
    @DisplayName("정상 생성")
    class ValidConstruction {

        @Test
        @DisplayName("올바른 node:port 형식이면 정상 생성된다")
        void createsWithValidFormat() {
            ConnectionDefinition connectionDefinition = new ConnectionDefinition("nodeA:out", "nodeB:in");

            assertThat(connectionDefinition.sourceNodeId()).isEqualTo("nodeA");
            assertThat(connectionDefinition.sourcePort()).isEqualTo("out");
            assertThat(connectionDefinition.targetNodeId()).isEqualTo("nodeB");
            assertThat(connectionDefinition.targetPort()).isEqualTo("in");
        }

        @Test
        @DisplayName("포트 이름에 콜론이 포함되어도 첫 콜론 기준으로만 분리한다")
        void splitsOnFirstColonOnly() {
            ConnectionDefinition connectionDefinition = new ConnectionDefinition("nodeA:out:extra", "nodeB:in");

            assertThat(connectionDefinition.sourceNodeId()).isEqualTo("nodeA");
            assertThat(connectionDefinition.sourcePort()).isEqualTo("out:extra");
        }
    }

    @Nested
    @DisplayName("null/빈 값 검증")
    class NullOrBlankValidation {

        @Test
        @DisplayName("from이 null이면 예외를 던진다")
        void nullFromThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition(null, "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from");
        }

        @Test
        @DisplayName("to가 null이면 예외를 던진다")
        void nullToThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:out", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("to");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("from이 빈 문자열이거나 공백뿐이면 예외를 던진다")
        void blankFromThrowsException(String blankValue) {
            assertThatThrownBy(() -> new ConnectionDefinition(blankValue, "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("to가 빈 문자열이거나 공백뿐이면 예외를 던진다")
        void blankToThrowsException(String blankValue) {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:out", blankValue))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("형식 검증 (node:port)")
    class FormatValidation {

        @Test
        @DisplayName("from에 콜론이 없으면 예외를 던진다")
        void fromWithoutColonThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA", "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("node:port");
        }

        @Test
        @DisplayName("to에 콜론이 없으면 예외를 던진다")
        void toWithoutColonThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:out", "nodeB"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("node:port");
        }
    }

    @Nested
    @DisplayName("빈 노드ID/포트명 방어 (콜론은 있으나 한쪽이 비어있는 경우)")
    class EmptyPartValidation {

        @Test
        @DisplayName("from이 'nodeA:' 형태(포트명 없음)면 예외를 던진다")
        void fromWithEmptyPortThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:", "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from");
        }

        @Test
        @DisplayName("from이 ':out' 형태(노드ID 없음)면 예외를 던진다")
        void fromWithEmptyNodeIdThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition(":out", "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from");
        }

        @Test
        @DisplayName("from이 ':' 뿐이면 예외를 던진다")
        void fromWithOnlyColonThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition(":", "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("to가 'nodeB:' 형태(포트명 없음)면 예외를 던진다")
        void toWithEmptyPortThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:out", "nodeB:"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("to");
        }

        @Test
        @DisplayName("to가 ':in' 형태(노드ID 없음)면 예외를 던진다")
        void toWithEmptyNodeIdThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:out", ":in"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("to");
        }

        @Test
        @DisplayName("from의 포트명이 공백뿐이면 예외를 던진다")
        void fromWithBlankPortThrowsException() {
            assertThatThrownBy(() -> new ConnectionDefinition("nodeA:  ", "nodeB:in"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}