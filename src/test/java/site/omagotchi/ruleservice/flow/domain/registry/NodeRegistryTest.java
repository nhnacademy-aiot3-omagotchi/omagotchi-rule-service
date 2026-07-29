package site.omagotchi.ruleservice.flow.domain.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class NodeRegistryTest {

    private NodeRegistry nodeRegistry;

    @BeforeEach
    void setUp() {
        nodeRegistry = new NodeRegistry();
    }

    private NodeDescriptor descriptorOf(String typeName) {
        AbstractNode dummyNode = mock(AbstractNode.class);
        return new NodeDescriptor(typeName, "설명", config -> dummyNode);
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("새로운 타입을 등록하면 조회 가능해진다")
        void registersNewType() {
            nodeRegistry.register(descriptorOf("SampleSource"));

            assertThat(nodeRegistry.isRegistered("SampleSource")).isTrue();
            assertThat(nodeRegistry.getRegisteredTypes()).containsExactly("SampleSource");
        }

        @Test
        @DisplayName("이미 등록된 타입을 다시 등록하면 IllegalStateException을 던진다")
        void duplicateTypeThrowsException() {
            nodeRegistry.register(descriptorOf("SampleSource"));

            assertThatThrownBy(() -> nodeRegistry.register(descriptorOf("SampleSource")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SampleSource");
        }

        @Test
        @DisplayName("nodeDescriptor가 null이면 IllegalArgumentException을 던진다")
        void nullDescriptorThrowsException() {
            assertThatThrownBy(() -> nodeRegistry.register(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("서로 다른 타입은 모두 등록된다")
        void registersMultipleDistinctTypes() {
            nodeRegistry.register(descriptorOf("SampleSource"));
            nodeRegistry.register(descriptorOf("SampleSink"));

            assertThat(nodeRegistry.getRegisteredTypes()).containsExactlyInAnyOrder("SampleSource", "SampleSink");
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("등록된 타입으로 노드를 생성하면 팩토리가 반환한 노드를 그대로 돌려준다")
        void createsNodeUsingRegisteredFactory() {
            AbstractNode expectedNode = mock(AbstractNode.class);
            nodeRegistry.register(new NodeDescriptor("SampleSource", "설명", config -> expectedNode));

            AbstractNode result = nodeRegistry.create("SampleSource", Map.of("id", "node-1"));

            assertThat(result).isSameAs(expectedNode);
        }

        @Test
        @DisplayName("팩토리에 config가 그대로 전달된다")
        void passesConfigToFactory() {
            Map<String, Object> givenConfig = Map.of("id", "node-1", "threshold", 42);
            Map<String, Object>[] capturedConfig = new Map[1];

            nodeRegistry.register(new NodeDescriptor("SampleSource", "설명", config -> {
                capturedConfig[0] = config;
                return mock(AbstractNode.class);
            }));

            nodeRegistry.create("SampleSource", givenConfig);

            assertThat(capturedConfig[0]).isEqualTo(givenConfig);
        }

        @Test
        @DisplayName("등록되지 않은 타입으로 생성하면 IllegalArgumentException을 던진다")
        void unknownTypeThrowsException() {
            assertThatThrownBy(() -> nodeRegistry.create("Ghost", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ghost");
        }

        @Test
        @DisplayName("등록되지 않은 타입 예외 메시지에 현재 등록된 타입 목록이 포함된다")
        void unknownTypeExceptionMessageContainsRegisteredTypes() {
            nodeRegistry.register(descriptorOf("SampleSource"));
            nodeRegistry.register(descriptorOf("SampleSink"));

            assertThatThrownBy(() -> nodeRegistry.create("Ghost", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SampleSource")
                    .hasMessageContaining("SampleSink");
        }

        @Test
        @DisplayName("typeName이 null이면 IllegalArgumentException을 던진다")
        void nullTypeNameThrowsException() {
            assertThatThrownBy(() -> nodeRegistry.create(null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("typeName이 빈 문자열이면 IllegalArgumentException을 던진다")
        void blankTypeNameThrowsException() {
            assertThatThrownBy(() -> nodeRegistry.create("  ", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("isRegistered")
    class IsRegistered {

        @Test
        @DisplayName("등록되지 않은 타입은 false를 반환한다")
        void returnsFalseForUnregisteredType() {
            assertThat(nodeRegistry.isRegistered("Ghost")).isFalse();
        }

        @Test
        @DisplayName("등록된 타입은 true를 반환한다")
        void returnsTrueForRegisteredType() {
            nodeRegistry.register(descriptorOf("SampleSource"));

            assertThat(nodeRegistry.isRegistered("SampleSource")).isTrue();
        }
    }

    @Nested
    @DisplayName("getRegisteredTypes")
    class GetRegisteredTypes {

        @Test
        @DisplayName("반환된 Set은 내부 상태와 독립적인 복사본이다")
        void returnsDefensiveCopy() {
            nodeRegistry.register(descriptorOf("SampleSource"));

            Set<String> types = nodeRegistry.getRegisteredTypes();

            assertThatThrownBy(() -> types.add("Injected"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}