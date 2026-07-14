package site.omagotchi.ruleservice.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        return response(exception.getErrorCode(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null
                        ? "요청값이 올바르지 않습니다."
                        : error.getDefaultMessage())
                .orElse("요청값이 올바르지 않습니다.");

        return response(
                CommonErrorCode.INVALID_REQUEST,
                message,
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            HttpServletRequest request
    ) {
        return response(CommonErrorCode.MALFORMED_REQUEST, request);
    }

    private ResponseEntity<ApiErrorResponse> response(
            ErrorCode errorCode,
            HttpServletRequest request
    ) {
        return response(errorCode, errorCode.message(), request);
    }

    private ResponseEntity<ApiErrorResponse> response(
            ErrorCode errorCode,
            String message,
            HttpServletRequest request
    ) {
        HttpStatus status = ErrorHttpStatusMapper.map(errorCode.type());

        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse(
                        status.value(),
                        errorCode.code(),
                        message,
                        request.getRequestURI()
                ));
    }
}
