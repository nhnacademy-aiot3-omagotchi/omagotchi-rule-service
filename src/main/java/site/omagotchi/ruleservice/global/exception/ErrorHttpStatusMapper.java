package site.omagotchi.ruleservice.global.exception;

import org.springframework.http.HttpStatus;

public final class ErrorHttpStatusMapper {

    private ErrorHttpStatusMapper() {
    }

    public static HttpStatus map(ErrorType type) {
        return switch (type) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case AUTHENTICATION -> HttpStatus.UNAUTHORIZED;
            case AUTHORIZATION -> HttpStatus.FORBIDDEN;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
