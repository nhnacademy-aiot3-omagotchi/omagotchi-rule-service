package site.omagotchi.ruleservice.global.exception;

public record ApiErrorResponse(
        String code,
        String message,
        String path,
        String requestId
) {
}
