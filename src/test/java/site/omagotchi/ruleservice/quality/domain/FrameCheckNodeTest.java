package site.omagotchi.ruleservice.quality.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FrameCheckNodeTest {

    private FrameCheckNode node;
    private RecordingConnection out;
    private RecordingConnection duplicate;
    private RecordingConnection delayed;
    private RecordingConnection missing;

    private static final Instant BASE = Instant.parse("2026-01-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        node = new FrameCheckNode("frame-check");
        out = new RecordingConnection();
        duplicate = new RecordingConnection();
        delayed = new RecordingConnection();
        missing = new RecordingConnection();
        node.getOutputPort("out").connect(out);
        node.getOutputPort("duplicate").connect(duplicate);
        node.getOutputPort("delayed").connect(delayed);
        node.getOutputPort("missing").connect(missing);
    }

    private Message message(String measurement, double value,
                            Instant measuredAt, Instant receivedAt, Long fCnt) {
        SensorReading reading = new SensorReading(
                "trace-1", "실습실", "전방", "eui-1", measurement,
                value, measuredAt, receivedAt, "sensor-1", fCnt);
        return Message.of("trace-1", Map.of("sensorReading", reading));
    }

    //중복
    @Test
    @DisplayName("처음 보는 데이터는 통과하고 아무 신고가 없다")
    void firstTimePassesTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));

        assertThat(out.messages()).hasSize(1);
        assertThat(duplicate.messages()).isEmpty();
        assertThat(delayed.messages()).isEmpty();
        assertThat(missing.messages()).isEmpty();
    }

    @Test
    @DisplayName("같은 fCnt의 같은 측정항목이 두 번 오면 통과 1건, 중복 신고 1건이다")
    void duplicateBlockedByFcntTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 650.0, BASE, BASE, 100L));

        assertThat(out.messages()).hasSize(1);
        assertThat(duplicate.messages()).hasSize(1);

        QualityEvent qualityEvent = duplicate.messages().get(0).get("qualityEvent");
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.DUPLICATE);
    }

    @Test
    @DisplayName("같은 프레임(같은 fCnt)의 다른 측정항목은 중복이 아니다 - 형제 오탐 방지")
    void siblingsOfSameFrameAreNotDuplicatesTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("temperature", 26.4, BASE, BASE, 100L));

        assertThat(out.messages()).hasSize(2);
        assertThat(duplicate.messages()).isEmpty();
    }

    @Test
    @DisplayName("measuredAt이 같아도 fCnt가 다르면 별개 프레임으로 통과한다")
    void differentFcntSameTimePassesTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE, BASE, 101L));

        assertThat(out.messages()).hasSize(2);
        assertThat(duplicate.messages()).isEmpty();
    }

    @Test
    @DisplayName("fCnt가 null이면 measuredAt 키로 폴백해 중복을 잡는다")
    void fallsBackToMeasuredAtWhenFcntNullTest() {
        node.process(message("co2", 650.0, BASE, BASE, null));
        node.process(message("co2", 650.0, BASE, BASE, null));

        assertThat(out.messages()).hasSize(1);
        assertThat(duplicate.messages()).hasSize(1);
    }

    //결측 - fCnt 갭
    @Test
    @DisplayName("fCnt 갭이 있으면 통과시키고 빠진 번호마다 결측 신고를 1건씩 발행한다")
    void fcntGapReportsMissingPerNumberTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 655.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 105L));

        assertThat(out.messages()).hasSize(2);
        assertThat(duplicate.messages()).isEmpty();
        assertThat(missing.messages()).hasSize(4);   // 101,102,103,104

        List<String> details = missing.messages().stream()
                .map(m -> m.<QualityEvent>get("qualityEvent").detail())
                .toList();
        assertThat(details).containsExactly(
                "결측: fCnt 101 누락",
                "결측: fCnt 102 누락",
                "결측: fCnt 103 누락",
                "결측: fCnt 104 누락");

        for (Message m : missing.messages()) {
            QualityEvent event = m.get("qualityEvent");
            assertThat(event.type()).isEqualTo(QualityEvent.Type.MISSING);
        }
    }

    @Test
    @DisplayName("한 건만 누락되면 결측 신고도 1건이다")
    void singleGapReportsSingleMissingEventTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 102L));

        assertThat(missing.messages()).hasSize(1);
        QualityEvent qualityEvent = missing.messages().get(0).get("qualityEvent");
        assertThat(qualityEvent.detail()).isEqualTo("결측: fCnt 101 누락");
    }

    @Test
    @DisplayName("정상적으로 +1씩 증가하면 결측 신고가 없다")
    void normalIncrementNoMissingTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 101L));

        assertThat(missing.messages()).isEmpty();
    }

    //순서역전
    @Test
    @DisplayName("fCnt가 역행하면 순서역전 로그만 남기고 신고하지 않는다")
    void reorderLogsOnlyWithoutEventTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 649.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 99L));

        assertThat(out.messages()).hasSize(2);
        assertThat(missing.messages()).isEmpty();
    }

    @Test
    @DisplayName("역행 이후에도 예전 최고값 기준 다음 프레임은 정상 판정을 그대로 한다")
    void keepsTrackingHighWaterMarkAfterBackwardJumpTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 649.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 99L));    // 역전, last는 100 유지
        node.process(message("co2", 651.0, BASE.plusSeconds(120), BASE.plusSeconds(120), 101L)); // 100+1과 정확히 일치

        assertThat(out.messages()).hasSize(3);
        assertThat(missing.messages()).isEmpty();   // 재정렬 뒤 오신고 없음 확인
    }

    //지연
    @Test
    @DisplayName("61초 늦게 도착한 데이터는 지연 표시를 달고 통과하며 신고도 발행한다")
    void delayedMarksAndReportsTest() {
        Instant receivedAt = BASE.plusSeconds(61);

        node.process(message("co2", 650.0, BASE, receivedAt, 300L));

        assertThat(out.messages()).hasSize(1);
        assertThat((Boolean) out.messages().get(0).get("_delayed")).isTrue();

        QualityEvent qualityEvent = delayed.messages().get(0).get("qualityEvent");
        assertThat(delayed.messages()).hasSize(1);
        assertThat(qualityEvent.type()).isEqualTo(QualityEvent.Type.DELAYED);
        assertThat(qualityEvent.detail()).isEqualTo("지연: 61초");
    }

    @Test
    @DisplayName("60초 이하 지연은 신고하지 않는다")
    void exactly60SecondsIsNotDelayedTest() {
        node.process(message("co2", 650.0, BASE, BASE.plusSeconds(60), 400L));

        assertThat(out.messages()).hasSize(1);
        assertThat(delayed.messages()).isEmpty();
        assertThat((Boolean) out.messages().get(0).get("_delayed")).isNull();
    }

    //fCnt 없는 경우
    @Test
    @DisplayName("fCnt가 없어도 데이터는 통과하고 지연 판정은 정상 동작한다")
    void noFcntStillPassesAndAppliesDelayJudgmentTest() {
        node.process(message("co2", 650.0, BASE, BASE.plusSeconds(61), null));

        assertThat(out.messages()).hasSize(1);
        assertThat((Boolean) out.messages().get(0).get("_delayed")).isTrue();
        assertThat(delayed.messages()).hasSize(1);
    }
    
    //리뷰 대응 - 프레임 단위 추적, 리셋 확정, 갭 상한
    @Test
    @DisplayName("프레임마다 object 구성이 달라도(battery 간헐 전송) 결측 오탐이 없다 - 프레임 단위 추적")
    void noFalseMissingWhenFieldAbsentFromSomeFramesTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("battery", 92.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 101L));
        node.process(message("co2", 651.0, BASE.plusSeconds(120), BASE.plusSeconds(120), 102L));

        assertThat(out.messages()).hasSize(3);
        assertThat(missing.messages()).isEmpty();
    }

    @Test
    @DisplayName("낮은 fCnt가 두 프레임 연속 이어지면 리셋으로 확정하고 이후 결측 감지가 정상 동작한다")
    void resetConfirmedByTwoConsecutiveLowFramesTest() {
        node.process(message("co2", 650.0, BASE, BASE, 94794L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 0L));   // 역전 후보
        node.process(message("co2", 652.0, BASE.plusSeconds(120), BASE.plusSeconds(120), 1L)); // 리셋 확정
        node.process(message("co2", 653.0, BASE.plusSeconds(180), BASE.plusSeconds(180), 3L)); // fCnt 2 누락

        assertThat(out.messages()).hasSize(4);
        assertThat(missing.messages()).hasSize(1);   // 리셋 이후에도 결측 감지가 살아있다
        QualityEvent event = missing.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).isEqualTo("결측: fCnt 2 누락");
    }

    @Test
    @DisplayName("갭이 상한을 넘으면 개별 신고 대신 요약 1건으로 발행한다")
    void hugeGapSummarizedInSingleEventTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 200L));

        assertThat(missing.messages()).hasSize(1);
        QualityEvent event = missing.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).isEqualTo("결측: fCnt 101~199 누락 (99건)");
    }


    @Test
    @DisplayName("갭이 정확히 20건이면 상한 이내라서 개별 신고 20건이 발행된다 (20/21 경계)")
    void gapSizeExactlyTwentyReportsIndividuallyTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 121L));

        assertThat(missing.messages()).hasSize(20);
        QualityEvent first = missing.messages().get(0).get("qualityEvent");
        QualityEvent last = missing.messages().get(19).get("qualityEvent");
        assertThat(first.detail()).isEqualTo("결측: fCnt 101 누락");
        assertThat(last.detail()).isEqualTo("결측: fCnt 120 누락");
    }

    @Test
    @DisplayName("갭이 21건이면 상한을 넘어서 요약 신고 1건으로 발행된다 (20/21 경계)")
    void gapSizeTwentyOneSummarizesIntoOneEventTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 122L));

        assertThat(missing.messages()).hasSize(1);
        QualityEvent event = missing.messages().get(0).get("qualityEvent");
        assertThat(event.detail()).isEqualTo("결측: fCnt 101~121 누락 (21건)");
    }

    @Test
    @DisplayName("대량 결측 요약 신고 이후에도 lastFcnt가 갱신되어 다음 메시지가 정상 통과한다 (return 버그 회귀 테스트)")
    void continuesNormalProcessingAfterHugeGapSummaryTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 200L));   // 요약 신고 발생
        node.process(message("co2", 652.0, BASE.plusSeconds(120), BASE.plusSeconds(120), 201L)); // 이어서 정상 +1

        assertThat(out.messages()).hasSize(3);
        assertThat(missing.messages()).hasSize(1);   // 요약 1건뿐, 3번째 메시지에서 추가 신고 없음
    }

    @Test
    @DisplayName("대량 결측 요약 신고 이후에도 지연 판정이 정상 동작한다")
    void delayedDetectionStillWorksAfterHugeGapSummaryTest() {
        node.process(message("co2", 650.0, BASE, BASE, 100L));
        node.process(message("co2", 651.0, BASE.plusSeconds(60), BASE.plusSeconds(60), 200L));   // 요약 신고 발생

        Instant measuredAt = BASE.plusSeconds(120);
        Instant receivedAt = measuredAt.plusSeconds(61);
        node.process(message("co2", 652.0, measuredAt, receivedAt, 201L));

        assertThat((Boolean) out.messages().get(2).get("_delayed")).isTrue();
        assertThat(delayed.messages()).hasSize(1);
    }
}