package site.omagotchi.ruleservice.global.exception;

public record ApiErrorResponse(
        int status,
        String code,
        String message,
        String path,
        String requestId
) {
}