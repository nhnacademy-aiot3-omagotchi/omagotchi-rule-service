package site.omagotchi.ruleservice.global.security;

/**
 * 엔진 간 내부 API 호출의 인증 헤더 이름
 * 송신측(RestClientConfig)과 수신측(InternalAuthFilter)이 반드시 같은 값을 써야 하므로 한 곳에서 공유
 * 한 쪽만 바뀌면 모든 내부 호출이 403이 되어 피어가 서로 AUTH_FAILED로 빠짐
 */
public final class InternalAuthHeader {

    public static final String NAME = "X-Internal-Token";

    private InternalAuthHeader() {

    }
}
