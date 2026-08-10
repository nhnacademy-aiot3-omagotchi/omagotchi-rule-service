package site.omagotchi.ruleservice.recovery.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 클래스의 산출물이 로그뿐이라 로그를 담아두는 appender를 붙여 검증한다.
 */
class RawFailureTrackerTest {

    private RawFailureTracker tracker;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        tracker = new RawFailureTracker();

        logger = (Logger) LoggerFactory.getLogger(RawFailureTracker.class);
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("연속 실패해도 시작 로그는 한 줄, 회복 로그에 구간 건수가 담긴다")
    void logsOncePerOutageTest() {
        tracker.onParked(new ConnectException());
        tracker.onParked(new ConnectException());
        tracker.onParked(new ConnectException());

        tracker.onSuccess();

        assertThat(appender.list).hasSize(2);

        ILoggingEvent start = appender.list.get(0);
        assertThat(start.getLevel()).isEqualTo(Level.ERROR);
        assertThat(start.getFormattedMessage()).contains("raw 적재 실패 시작");
        assertThat(start.getThrowableProxy()).isNotNull(); // 스택트레이스는 시작 로그에만

        ILoggingEvent recovery = appender.list.get(1);
        assertThat(recovery.getLevel()).isEqualTo(Level.INFO);
        assertThat(recovery.getFormattedMessage()).contains("raw 적재 회복").contains("3");
        assertThat(recovery.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("정상 구간에서는 아무 로그도 남기지 않는다")
    void silentOnNormalPathTest() {
        tracker.onSuccess();
        tracker.onSuccess();

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("회복 후 다시 실패하면 건수가 0부터 다시 센다")
    void countResetsAfterRecoveryTest() {
        tracker.onParked(new ConnectException());
        tracker.onSuccess();
        appender.list.clear();

        tracker.onParked(new ConnectException());
        tracker.onParked(new ConnectException());
        tracker.onSuccess();

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("raw 적재 실패 시작");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("2"); // 앞 구간 1건이 누적되지 않는다
    }
}
