package site.omagotchi.ruleservice.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import site.omagotchi.ruleservice.core.engine.exception.FlowErrorCode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private static final String REQUEST_URI = "/flows/flow-1";
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    @Mock
    private HttpServletRequest request;

    @Mock
    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    void setUp() {
        this.globalExceptionHandler = new GlobalExceptionHandler();
        when(request.getRequestURI()).thenReturn(REQUEST_URI);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("BusinessException은 자신의 ErrorCode에 맞는 상태, 코드, 메시지로 응답한다")
    void handlesBusinessException() {
        BusinessException e = new BusinessException(CommonErrorCode.INVALID_REQUEST);

        ResponseEntity<ApiErrorResponse> response = this.globalExceptionHandler.handleBusinessException(e, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("COMMON_INVALID_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("요청값이 올바르지 않습니다.");
        assertThat(response.getBody().path()).isEqualTo(REQUEST_URI);
    }

    @Test
    @DisplayName("detail이 있으면 응답 message에 에러코드 메시지와 함께 실린다")
    void includesDetailInResponseMessage() {
        BusinessException exception = new BusinessException(FlowErrorCode.NODE_CONFIG_REJECTED, "flowId = flow-1, nodeId = node-a, reason = threshold는 0 이상이어야 합니다");

        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleBusinessException(exception, request);

        assertThat(response.getBody().message()).isEqualTo(FlowErrorCode.NODE_CONFIG_REJECTED.message() + " - flowId = flow-1, nodeId = node-a, reason = threshold는 0 이상이어야 합니다");
    }

    @Test
    @DisplayName("검증 실패 시 필드 오류의 기본 메시지를 사용해 400으로 응답한다")
    void handlesValidationExceptionWithFieldMessage() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("dto", "config", "config는 null일 수 없습니다.");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleValidationException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("config는 null일 수 없습니다.");
    }

    @Test
    @DisplayName("필드 오류가 없으면 기본 안내 메시지로 응답한다")
    void handlesValidationExceptionWithoutFieldErrors() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleValidationException(exception, request);

        assertThat(response.getBody().message()).isEqualTo("요청값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("필드 오류는 있지만 기본 메시지가 null이면 기본 안내 메시지로 대체한다")
    void handlesValidationExceptionWithNullDefaultMessage() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("dto", "config", null, false, null, null, null);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleValidationException(exception, request);

        assertThat(response.getBody().message()).isEqualTo("요청값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("요청 본문을 읽을 수 없으면 MALFORMED_REQUEST로 400 응답한다")
    void handlesMalformedRequest() {
        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleMalformedRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("COMMON_MALFORMED_REQUEST");
    }

    @Test
    @DisplayName("처리되지 않은 예외는 500(COMMON_INTERNAL_ERROR)으로 응답한다")
    void handlesUnexpectedException() {
        RuntimeException exception = new RuntimeException("예상 못한 오류");

        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleUnexpectedException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("COMMON_INTERNAL_ERROR");
    }

    @Test
    @DisplayName("MDC에 있는 requestId가 응답 바디에 그대로 실린다")
    void includesRequestIdFromMdc() {
        MDC.put(MDC_REQUEST_ID_KEY, "test-request-id");
        BusinessException exception = new BusinessException(CommonErrorCode.INTERNAL_ERROR);

        ResponseEntity<ApiErrorResponse> response = globalExceptionHandler.handleBusinessException(exception, request);

        assertThat(response.getBody().requestId()).isEqualTo("test-request-id");
    }
}