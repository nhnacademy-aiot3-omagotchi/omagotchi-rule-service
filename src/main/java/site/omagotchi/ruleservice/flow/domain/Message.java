package site.omagotchi.ruleservice.flow.domain;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 불변 클래스
 */
@Getter
public final class Message {

    private final String id; // 메시지 고유 ID
    private final String traceId; // 파이프라인 전 구간 추적
    private final Map<String, Object> headers; // 메타데이터
    private final Map<String, Object> payload; // 데이터
    private final long timestamp;

    private Message(String traceId, Map<String, Object> headers, Map<String, Object> payload) {
        this.id = UUID.randomUUID().toString();
        this.traceId = traceId;
        this.headers = Map.copyOf(headers);
        this.payload = Map.copyOf(payload);
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * id(UUID), traceId(UUID), timestamp 자동 생성
     */
    public static Message of(Map<String, Object> payload) {
        return of(UUID.randomUUID().toString(), payload);
    }

    /** 기존 traceId 승계용 정적 팩토리 메서드 */
    public static Message of(String traceId, Map<String, Object> payload) {

        if (Objects.isNull(traceId) || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId가 null이거나 비어있습니다.");
        }

        if (Objects.isNull(payload)) {
            throw new IllegalArgumentException("payload가 null입니다.");
        }

        return new Message(
                traceId,
                Map.of(),
                payload
        );
    }

    /**
     * payload에서 키로 값 조회 (제네릭 캐스팅)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) payload.get(key);
    }

    //with 메서드 id는 새로 traceId는 승계한 Message 반환
    /**
     * payload 항목을 추가한 새 Message 반환
     */
    public Message withEntry(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(payload);
        copy.put(key, value);
        return new Message(traceId, headers, copy);
    }

    /**
     * payload 항목을 제거한 새 Message 반환
     */
    public Message withoutKey(String key) {
        Map<String, Object> copy = new HashMap<>(payload);
        copy.remove(key);
        return new Message(traceId, headers, copy);
    }

    /**
     * header를 추가한 새 Message 반환
     */
    public Message withHeader(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(headers);
        copy.put(key, value);
        return new Message(traceId, copy, payload);
    }

    public boolean hasKey(String key) {
        return payload.containsKey(key);
    }

    /**
     * Message 내용 반환 traceId 8자 + payload 내용
     */
    @Override
    public String toString() {
        String shortTraceId = this.traceId.length() >= 8
                ? this.traceId.substring(0, 8)
                : this.traceId;

        String summary = payload.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
        return "Message[" + shortTraceId + "] " + summary;
    }
}