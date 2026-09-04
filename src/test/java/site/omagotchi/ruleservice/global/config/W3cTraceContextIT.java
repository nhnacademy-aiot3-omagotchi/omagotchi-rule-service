package site.omagotchi.ruleservice.global.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import site.omagotchi.ruleservice.global.requestid.RequestId;
import site.omagotchi.ruleservice.global.requestid.RequestIdRestClientInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

@SpringBootTest(
        classes = W3cTraceContextIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class W3cTraceContextIT {

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private Tracer tracer;

    @Test
    @DisplayName("자동 구성 RestClient의 Request ID와 W3C traceparent 전파")
    void propagatesW3cTraceparentWithAutoConfiguredRestClientBuilder() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(this.restClientBuilder).build();
        RestClient restClient = this.restClientBuilder
                .baseUrl("http://trace.test")
                .requestInterceptor(new RequestIdRestClientInterceptor())
                .build();
        Span parent = this.tracer.nextSpan().name("w3c-propagation-test").start();

        server.expect(requestTo("http://trace.test/probe"))
                .andExpect(request -> {
                    String traceparent = request.getHeaders().getFirst("traceparent");
                    assertThat(traceparent).matches(
                            "^00-" + parent.context().traceId() + "-[0-9a-f]{16}-[0-9a-f]{2}$"
                    );
                    assertThat(request.getHeaders().getFirst(RequestId.HEADER_NAME))
                            .matches("^[0-9a-f]{32}$");
                    assertThat(request.getHeaders().getFirst("baggage")).isNull();
                })
                .andRespond(withNoContent());

        try (Tracer.SpanInScope ignored = this.tracer.withSpan(parent)) {
            restClient.get()
                    .uri("/probe")
                    .retrieve()
                    .toBodilessEntity();
        } finally {
            parent.end();
        }

        server.verify();
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
