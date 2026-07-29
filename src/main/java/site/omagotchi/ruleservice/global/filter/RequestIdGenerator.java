package site.omagotchi.ruleservice.global.filter;

import java.util.UUID;

/**
 * 공통 Request ID 형식(소문자 16진수 32자리)에 맞는 값 발급
 */
public final class RequestIdGenerator {

    private RequestIdGenerator() {

    }

    /**
     * UUID.randomUUID().toString()은 항상 xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx 형식으로 나옴
     * (32개의 16진수 자리 + 하이픈 4개 = 36자, Java의 UUID.toString()은 항상 소문자로만 출력)
     * .replace("-", "")로 하이픈 4개를 지우면: 남는 문자는 정확히 32자, 전부 0-9, a-f 범위의 소문자 16진수 문자 뿐
     * ^[0-9a-f]{32}$ 정규식에 정확히 맞음
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}