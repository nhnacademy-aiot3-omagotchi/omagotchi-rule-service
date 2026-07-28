package site.omagotchi.ruleservice.core.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    @Test
    @DisplayName("생성 시 id, traceId가 null/빈 문자열이 아니고 timestamp가 0보다 크다")
    void generatesFieldsAutomaticallyOnCreation() {
        Message message = Message.of(Map.of("key", "value"));

        assertThat(message.getId()).isNotBlank();
        assertThat(message.getTraceId()).isNotBlank();
        assertThat(message.getTimestamp()).isGreaterThan(0);
    }

    @Test
    @DisplayName("생성 시 넣은 key-value를 get()으로 조회할 수 있다")
    void retrievesPayloadByKey() {
        Message message = Message.of(Map.of("value", 27.9));

        Double value = message.get("value");

        assertThat(value).isEqualTo(27.9);
    }

    @Test
    @DisplayName("get()은 캐스팅 코드 없이 제네릭으로 값을 리턴한다")
    void getReturnsValueWithGenericCastingWithoutExplicitCast() {
        Message message = Message.of(Map.of("value", 27.9, "name", "co2"));

        Double doubleValue = message.get("value");
        String stringValue = message.get("name");

        assertThat(doubleValue).isEqualTo(27.9);
        assertThat(stringValue).isEqualTo("co2");
    }

    @Test
    @DisplayName("없는 키를 조회하면 null을 리턴한다")
    void returnsNullForMissingKey() {
        Message message = Message.of(Map.of("value", 27.9));

        String result = message.get("없는키");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("payload는 불변이라 직접 수정하면 UnsupportedOperationException 발생한다")
    void payloadIsImmutable() {
        Message message = Message.of(Map.of("value", 27.9));

        assertThatThrownBy(() -> message.getPayload().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("생성에 쓴 원본 Map을 수정해도 Message에는 반영되지 않는다")
    void originalMapMutationDoesNotAffectMessage() {
        Map<String, Object> original = new HashMap<>();
        original.put("value", 27.9);

        // of 내부에서 new Message로 생성되는데, 생성자에서 payload를 Map.copyOf(payload)로 초기화함
        Message message = Message.of(original);
        original.put("value", 999.0);
        original.put("extra", "injected");

        assertThat(message.<Double>get("value")).isEqualTo(27.9);
        assertThat(message.hasKey("extra")).isFalse();
    }

    @Test
    @DisplayName("withEntry()는 새 Message를 반환하고, 원본에는 새 키가 없다")
    void withEntryReturnsNewMessageWithoutMutatingOriginal() {
        Message original = Message.of(Map.of("value", 27.9));

        Message updated = original.withEntry("unit", "ppm");

        assertThat(updated).isNotSameAs(original);
        assertThat(original.hasKey("unit")).isFalse();
        assertThat(updated.hasKey("unit")).isTrue();
    }

    @Test
    @DisplayName("withEntry() 결과의 traceId는 원본과 같고, id는 다르다")
    void withEntryPreservesTraceIdButGeneratesNewId() {
        Message original = Message.of(Map.of("value", 27.9));

        // withEntry 내부적으로 new Message로 생성할 때 원본 traceId를 파라미터로 넣어줌 -> 생성자에서 id는 UUID로 새로 생성함
        Message updated = original.withEntry("unit", "ppm");

        assertThat(updated.getTraceId()).isEqualTo(original.getTraceId());
        assertThat(updated.getId()).isNotEqualTo(original.getId());
    }

    @Test
    @DisplayName("Message.of(traceId, payload)는 지정한 traceId를 그대로 유지한다")
    void ofWithTraceIdKeepsSpecifiedTraceId() {
        String traceId = "fixed-trace-id";

        Message message = Message.of(traceId, Map.of("value", 27.9));

        assertThat(message.getTraceId()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("withHeader() 후 원본 헤더는 불변이고, 새 Message에는 헤더가 존재한다")
    void withHeaderAddsHeaderToNewMessageOnly() {
        Message original = Message.of(Map.of("value", 27.9));

        Message updated = original.withHeader("source", "mqtt");

        assertThat(original.getHeaders()).isEmpty();
        assertThat(updated.getHeaders()).containsEntry("source", "mqtt");
    }

    @Test
    @DisplayName("withoutKey()는 해당 키가 제거된 새 Message를 반환하고, 원본에는 키가 남아있다")
    void withoutKeyRemovesKeyOnlyFromNewMessage() {
        Message original = Message.of(Map.of("value", 27.9, "unit", "ppm"));

        Message updated = original.withoutKey("unit");

        assertThat(updated.hasKey("unit")).isFalse();
        assertThat(original.hasKey("unit")).isTrue();
    }

    @Test
    @DisplayName("toString()은 null이 아니고 traceId 일부와 payload 내용을 포함한다")
    void toStringContainsTraceIdPrefixAndPayload() {
        Message message = Message.of(Map.of("value", 27.9));
        String shortTraceId = message.getTraceId().substring(0, 8);

        String result = message.toString();

        assertThat(result)
                .isNotNull()
                .contains(shortTraceId)
                .contains("value")
                .contains("27.9");
    }
}