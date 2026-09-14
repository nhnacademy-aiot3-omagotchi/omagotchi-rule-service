package site.omagotchi.ruleservice.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;

@RestControllerAdvice
@NullMarked
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final RuleErrorEventLogger errorEventLogger;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        return buildErrorResponse(exception.getErrorCode(), request);
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

        return buildFrameworkErrorResponse(
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
        return buildFrameworkErrorResponse(
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
            this.errorEventLogger.logUnexpected(
                    exception,
                    statusCode.value(),
                    ((ServletWebRequest) request).getRequest()
            );
        }
        return buildFrameworkErrorResponse(
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
        this.errorEventLogger.logUnexpected(
                exception,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                request
        );
        return buildErrorResponse(CommonErrorCode.INTERNAL_SERVER_ERROR, request);
    }

    private @Nullable ResponseEntity<Object> buildFrameworkErrorResponse(
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
                MDC.get(RequestIdContext.MDC_KEY)
        );
        return new ResponseEntity<>(
                body,
                springResponse.getHeaders(),
                springResponse.getStatusCode()
        );
    }

    private ResponseEntity<ApiErrorResponse> buildErrorResponse(
            ErrorCode errorCode,
            HttpServletRequest request
    ) {
        HttpStatus status = ErrorHttpStatusMapper.map(errorCode.type());

        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse(
                        errorCode.code(),
                        errorCode.message(),
                        request.getRequestURI(),
                        MDC.get(RequestIdContext.MDC_KEY)
                ));
    }
}
