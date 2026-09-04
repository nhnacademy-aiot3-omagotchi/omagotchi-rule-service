package site.omagotchi.ruleservice.global.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import site.omagotchi.ruleservice.global.exception.GlobalExceptionHandler;
import site.omagotchi.ruleservice.global.exception.RuleErrorEventLogger;
import site.omagotchi.ruleservice.global.requestid.RequestId;
import site.omagotchi.ruleservice.global.requestid.RequestIdFilter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RuleHttpObservabilityIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "logging.level.site.omagotchi.ruleservice.global.logging.HttpAccessLogFilter=INFO",
                "logging.structured.format.console=ecs",
                "logging.structured.ecs.service.environment=test",
                "logging.structured.json.stacktrace.max-length=4096"
        }
)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RuleHttpObservabilityIT {

    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final String FAILURE_DETAIL = "local-rule-diagnostic-detail";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("Rule 접근 로그에 Request ID와 W3C Trace 식별자를 함께 기록")
    void recordsRequestAndTraceIdentifiers(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + this.port + "/probe"))
                .header(RequestId.HEADER_NAME, REQUEST_ID)
                .header("traceparent", TRACEPARENT)
                .GET()
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode accessEvent = findEvent(output, "rule-service.http");

        assertThat(accessEvent.at("/http/request/id").asString()).isEqualTo(REQUEST_ID);
        assertThat(accessEvent.at("/trace/id").asString()).isEqualTo(TRACE_ID);
        assertThat(accessEvent.at("/span/id").asString()).matches("^[0-9a-f]{16}$");
        assertThat(accessEvent.has("traceId")).isFalse();
        assertThat(accessEvent.has("spanId")).isFalse();
    }

    @Test
    @DisplayName("예상하지 못한 500의 검색 식별자와 로컬 예외 원인 기록")
    void recordsUnexpectedFailureWithLocalDiagnostic(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + this.port + "/failure"))
                .header(RequestId.HEADER_NAME, REQUEST_ID)
                .header("traceparent", TRACEPARENT)
                .GET()
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode accessEvent = findEvent(output, "rule-service.http");
        JsonNode errorEvent = findEvent(output, "rule-service.error");
        JsonNode diagnosticEvent = findEvent(output, "rule-service.diagnostic");
        assertThat(accessEvent.at("/http/response/status_code").asInt()).isEqualTo(500);
        assertThat(errorEvent.at("/http/request/id").asString()).isEqualTo(REQUEST_ID);
        assertThat(errorEvent.at("/trace/id").asString()).isEqualTo(TRACE_ID);
        assertThat(errorEvent.at("/span/id").asString()).matches("^[0-9a-f]{16}$");
        assertThat(errorEvent.at("/error/code").asString())
                .isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(errorEvent.at("/error/type").asString())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(diagnosticEvent.at("/event/id").asString())
                .isEqualTo(errorEvent.at("/event/id").asString());
        assertThat(diagnosticEvent.at("/error/message").asString()).isEqualTo(FAILURE_DETAIL);
        assertThat(diagnosticEvent.at("/error/stack_trace").asString()).contains(FAILURE_DETAIL);
        assertThat(diagnosticEvent.at("/trace/id").asString()).isEqualTo(TRACE_ID);
    }

    private static JsonNode findEvent(CapturedOutput output, String dataset) {
        List<JsonNode> matchingEvents = output.getAll().lines()
                .map(String::trim)
                .filter(line -> line.startsWith("{"))
                .map(RuleHttpObservabilityIT::readJson)
                .filter(json -> dataset.equals(
                        json.at("/event/dataset").asString()))
                .toList();
        assertThat(matchingEvents).singleElement();
        return matchingEvents.getFirst();
    }

    private static JsonNode readJson(String line) {
        try {
            return JSON.readTree(line);
        } catch (Exception exception) {
            throw new AssertionError("구조화 로그 JSON 해석 실패", exception);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({ProbeController.class, TestSecurityConfiguration.class, RequestIdFilter.class,
            HttpAccessLogFilter.class, GlobalExceptionHandler.class, RuleErrorEventLogger.class})
    static class TestApplication {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        void probe() {
        }

        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException(FAILURE_DETAIL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSecurityConfiguration {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }
}
