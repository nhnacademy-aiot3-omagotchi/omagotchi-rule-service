package site.omagotchi.ruleservice.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
@NullMarked
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String MDC_REQUEST_ID_KEY = "requestId";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        return response(exception.getErrorCode(), request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .filter(error -> error.getDefaultMessage() != null)
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("요청값이 올바르지 않습니다.");

        return frameworkResponse(
                exception,
                CommonErrorCode.INVALID_REQUEST,
                message,
                headers,
                statusCode,
                request
        );
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        return frameworkResponse(
                exception,
                CommonErrorCode.MALFORMED_REQUEST,
                CommonErrorCode.MALFORMED_REQUEST.message(),
                headers,
                statusCode,
                request
        );
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        ErrorCode errorCode = statusCode.is5xxServerError()
                ? CommonErrorCode.INTERNAL_SERVER_ERROR
                : CommonErrorCode.INVALID_REQUEST;

        if (statusCode.is5xxServerError()) {
            logUnexpected(exception, ((ServletWebRequest) request).getRequest());
        }
        return frameworkResponse(
                exception,
                errorCode,
                errorCode.message(),
                headers,
                statusCode,
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        logUnexpected(exception, request);
        return response(CommonErrorCode.INTERNAL_SERVER_ERROR, request);
    }

    private @Nullable ResponseEntity<Object> frameworkResponse(
            Exception exception,
            ErrorCode errorCode,
            String message,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        ResponseEntity<Object> springResponse = super.handleExceptionInternal(
                exception,
                null,
                headers,
                statusCode,
                request
        );
        if (springResponse == null) {
            return null;
        }

        ApiErrorResponse body = new ApiErrorResponse(
                errorCode.code(),
                message,
                ((ServletWebRequest) request).getRequest().getRequestURI(),
                MDC.get(MDC_REQUEST_ID_KEY)
        );
        // 요청 URI는 HTML이 아닌 JSON 문자열로 직렬화되므로 XSS 실행 문맥이 아님
        return new ResponseEntity<>(
                body,
                springResponse.getHeaders(),
                springResponse.getStatusCode()
        );
    }

    private void logUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "예상하지 못한 서버 오류 code={}, exception={}, method={}, path={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR.code(),
                exception.getClass().getName(),
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            ErrorCode errorCode,
            HttpServletRequest request
    ) {
        HttpStatus status = ErrorHttpStatusMapper.map(errorCode.type());

        // 요청 URI는 HTML이 아닌 JSON 문자열로 직렬화되므로 XSS 실행 문맥이 아님
        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse(
                        errorCode.code(),
                        errorCode.message(),
                        request.getRequestURI(),
                        MDC.get(MDC_REQUEST_ID_KEY)
                ));
    }
}
