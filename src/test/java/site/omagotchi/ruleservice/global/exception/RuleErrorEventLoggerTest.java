package site.omagotchi.ruleservice.global.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.Mockito.mock;

class RuleErrorEventLoggerTest {

    @Test
    @DisplayName("중앙 오류의 호출 위치와 로컬 원본 진단 분리")
    void recordsLocationsWithoutExceptionMessages() {
        // Given
        RuleErrorEventLogger eventLogger = new RuleErrorEventLogger(mock(Tracer.class));
        IllegalStateException failure = new IllegalStateException("token=private-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rules");
        Logger logger = (Logger) LoggerFactory.getLogger(RuleErrorEventLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            // When
            eventLogger.logUnexpected(failure, 500, request);

            // Then
            then(appender.list).hasSize(2);
            ILoggingEvent safe = appender.list.getFirst();
            Map<String, Object> fields = safe.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            then(fields).containsEntry("event.dataset", "rule-service.error");
            then((String) fields.get("error.stack_trace"))
                    .contains("RuleErrorEventLoggerTest.recordsLocationsWithoutExceptionMessages(")
                    .doesNotContain("private-token");
            then(safe.getThrowableProxy()).isNull();
            then(appender.list.getLast().getThrowableProxy().getMessage()).contains("private-token");
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
