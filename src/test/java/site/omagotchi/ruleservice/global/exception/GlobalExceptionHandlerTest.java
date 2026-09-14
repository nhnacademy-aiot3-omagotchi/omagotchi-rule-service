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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import site.omagotchi.ruleservice.global.requestid.RequestIdContext;
import site.omagotchi.ruleservice.flow.application.FlowErrorCode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private static final String REQUEST_URI = "/flows/flow-1";
    private static final String DIAGNOSTIC_MESSAGE =
            "flowId = flow-1, nodeId = node-a, threshold = -1, expected = 0 이상";

    @Mock
    private HttpServletRequest request;

    @Mock
    private RuleErrorEventLogger errorEventLogger;

    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    void setUp() {
        this.globalExceptionHandler = new GlobalExceptionHandler(this.errorEventLogger);
        when(request.getRequestURI()).thenReturn(REQUEST_URI);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("BusinessException의 ErrorCode 기준 응답")
    void handlesBusinessException() {
        // Given
        BusinessException exception =
                new BusinessException(CommonErrorCode.INVALID_REQUEST);

        // When
        ResponseEntity<ApiErrorResponse> response =
                globalExceptionHandler.handleBusinessException(exception, request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("COMMON_INVALID_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("요청값이 올바르지 않습니다.");
        assertThat(response.getBody().path()).isEqualTo(REQUEST_URI);
    }

    @Test
    @DisplayName("외부 응답에서 진단 메시지 숨김")
    void hidesDiagnosticMessageFromResponse() {
        // Given
        BusinessException exception = new BusinessException(
                FlowErrorCode.NODE_CONFIG_REJECTED,
                DIAGNOSTIC_MESSAGE
        );

        // When
        ResponseEntity<ApiErrorResponse> response =
                globalExceptionHandler.handleBusinessException(exception, request);

        // Then
        assertThat(response.getBody().message()).isEqualTo(FlowErrorCode.NODE_CONFIG_REJECTED.message());
    }

    @Test
    @DisplayName("검증 실패 시 필드 오류 기본 메시지로 400 응답")
    void handlesValidationExceptionWithFieldMessage() {
        // Given
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("dto", "config", "config는 null일 수 없습니다.");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        // When
        ResponseEntity<Object> response = globalExceptionHandler.handleMethodArgumentNotValid(
                exception,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request)
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).message()).isEqualTo("config는 null일 수 없습니다.");
    }

    @Test
    @DisplayName("필드 오류 부재 시 기본 안내 메시지 응답")
    void handlesValidationExceptionWithoutFieldErrors() {
        // Given
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        // When
        ResponseEntity<Object> response = globalExceptionHandler.handleMethodArgumentNotValid(
                exception,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request)
        );

        // Then
        assertThat(body(response).message()).isEqualTo("요청값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("필드 오류 기본 메시지 null 시 기본 안내 메시지 대체")
    void handlesValidationExceptionWithNullDefaultMessage() {
        // Given
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("dto", "config", null, false, null, null, null);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        // When
        ResponseEntity<Object> response = globalExceptionHandler.handleMethodArgumentNotValid(
                exception,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request)
        );

        // Then
        assertThat(body(response).message()).isEqualTo("요청값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("읽을 수 없는 요청 본문을 MALFORMED_REQUEST 400으로 응답")
    void handlesMalformedRequest() {
        // Given
        HttpMessageNotReadableException exception = mock(HttpMessageNotReadableException.class);

        // When
        ResponseEntity<Object> response = globalExceptionHandler.handleHttpMessageNotReadable(
                exception,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request)
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).code()).isEqualTo("COMMON_MALFORMED_REQUEST");
    }

    @Test
    @DisplayName("호출 계약 위반을 500(COMMON_INTERNAL_SERVER_ERROR)으로 숨김")
    void hidesIllegalArgumentException() {
        // Given
        IllegalArgumentException exception =
                new IllegalArgumentException("외부에 노출하면 안 되는 인자 정보");

        // When
        ResponseEntity<ApiErrorResponse> response =
                globalExceptionHandler.handleUnexpectedException(exception, request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR.message());
    }

    @Test
    @DisplayName("내부 상태 위반을 500(COMMON_INTERNAL_SERVER_ERROR)으로 숨김")
    void hidesIllegalStateException() {
        // Given
        IllegalStateException exception =
                new IllegalStateException("외부에 노출하면 안 되는 상태 정보");

        // When
        ResponseEntity<ApiErrorResponse> response =
                globalExceptionHandler.handleUnexpectedException(exception, request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR.message());
    }

    @Test
    @DisplayName("MDC requestId의 응답 Body 반영")
    void includesRequestIdFromMdc() {
        // Given
        MDC.put(RequestIdContext.MDC_KEY, "test-request-id");
        BusinessException exception = new BusinessException(CommonErrorCode.INVALID_REQUEST);

        // When
        ResponseEntity<ApiErrorResponse> response =
                globalExceptionHandler.handleBusinessException(exception, request);

        // Then
        assertThat(response.getBody().requestId()).isEqualTo("test-request-id");
    }

    private ApiErrorResponse body(ResponseEntity<Object> response) {
        return (ApiErrorResponse) response.getBody();
    }
}
