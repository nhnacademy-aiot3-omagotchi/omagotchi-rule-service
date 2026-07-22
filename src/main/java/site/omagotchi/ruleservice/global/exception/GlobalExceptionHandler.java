package site.omagotchi.ruleservice.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import site.omagotchi.ruleservice.core.engine.exception.DuplicateFlowException;
import site.omagotchi.ruleservice.core.engine.exception.FlowManagerException;
import site.omagotchi.ruleservice.core.engine.exception.FlowNotFoundException;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String MDC_TRACE_ID_KEY = "traceId";

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

    @ExceptionHandler(FlowManagerException.class)
    public ResponseEntity<ApiErrorResponse> handleFlowManagerException(
            FlowManagerException exception,
            HttpServletRequest request
    ) {
        if (Objects.isNull(exception.getFlowErrorCode())) {
            // FlowErrorCode 없이 던져진 기존 경로(예: 노드 id 불일치) -> 500으로 처리
            log.error("[{}] FlowErrorCode 없는 FlowManagerException 발생", request.getRequestURI(), exception);
            return response(CommonErrorCode.INTERNAL_ERROR, request);
        }

        return response(exception.getFlowErrorCode(), request);
    }

    // 처리되지 않은 예외가 Spring 기본 에러 응답으로 새는 것을 막는 fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {

        // fallback 핸들러에서 원본 예외를 로그로 남김 (traceId로 응답은 추적되는데 서버 로그에서 원인 못 찾는 문제 방지)
        log.error("[{}] 처리되지 않은 예외 발생", request.getRequestURI(), exception);
        return response(CommonErrorCode.INTERNAL_ERROR, request);
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
        String traceId = MDC.get(MDC_TRACE_ID_KEY);

        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse(
                        status.value(),
                        errorCode.code(),
                        message,
                        request.getRequestURI(),
                        traceId
                ));
    }
}