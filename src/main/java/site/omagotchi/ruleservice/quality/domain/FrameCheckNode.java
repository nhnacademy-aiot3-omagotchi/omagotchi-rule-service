package site.omagotchi.ruleservice.quality.domain;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.time.Duration;
import java.util.Map;

/**
 * 프레임 도착 품질 검사. 값이 아니라 프레임 메타데이터(fCnt·시각)만 본다.
 * 중복(DUPLICATE)·지연(DELAYED)·결측(MISSING)은 이벤트로 발행하고,
 * 순서역전·fCnt리셋은 로그로만 남긴다.
 */
@Slf4j
public class FrameCheckNode extends AbstractNode {

    private static final int MAX_MISSING_REPORTS = 20;

            private final Cache<String,Boolean> seen = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100000)
            .build();

    // eui -> 확정된 마지막 fCnt (결측·순서역전 감지용)
    private final Cache<String, Long> lastFcnt = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(24))
            .maximumSize(10000)
            .build();

    // eui -> 확정되지 않은 마지막 fCnt. (리셋(재조인) 감지용)
    private final Cache<String, Long> resetCandidate = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(10000)
            .build();


    public FrameCheckNode(String id) {
        super(id);
        addInputPort("in");
        addOutputPort("out");
        addOutputPort("duplicate");
        addOutputPort("delayed");
        addOutputPort("missing");
    }

    @Override
    protected void onProcess(Message message) {
        SensorReading sensorReading = message.get("sensorReading");

        String key = sensorReading.fCnt() != null
                ? sensorReading.deviceEui()+":"+sensorReading.measurement()+":"+sensorReading.fCnt()
                : sensorReading.deviceEui()+":"+sensorReading.measurement()+":"+sensorReading.measuredAt();

        Duration gap = Duration.between(sensorReading.measuredAt(),sensorReading.receivedAt());

        //중복 판정
        if (seen.getIfPresent(key) != null){

            log.info("[중복] {}:{} key={}", sensorReading.deviceEui(), sensorReading.measurement(), key);

            QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.DUPLICATE,"중복: "+key);
            send("duplicate", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));
            return;
        }
        else {
            seen.put(key, true);
        }

        //결측 판정(+ 순서역전, 리셋)
        if (sensorReading.fCnt() != null) {
            long fCnt = sensorReading.fCnt();
            String eui = sensorReading.deviceEui();
            Long last = lastFcnt.getIfPresent(eui);


            if (last == null) {             // ① 처음 보는 센서 - 기준값만 저장
                lastFcnt.put(eui, fCnt);

            } else if (fCnt == last) {      // ② 같은 프레임의 다른 측정항목 (정상)

            } else if (fCnt == last + 1) {  // ③ 정상적으로 1씩 증가 - 기준값 갱신
                lastFcnt.put(eui, fCnt);
                resetCandidate.invalidate(eui);

            } else if (fCnt > last + 1) {   // ④ 갭 크기에 따라 개별/요약 신고로 갈림
                long gapSize = fCnt - last - 1;

                if (gapSize > MAX_MISSING_REPORTS) {    // 갭이 너무 크면 개별 신고 대신 요약 1건으로 (로그 폭주 방지)
                    log.warn("[결측] {} fCnt {}~{} 누락 ({}건 일괄)", sensorReading.deviceEui(), last + 1, fCnt - 1, gapSize);
                    QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.MISSING,
                            "결측: fCnt " + (last + 1) + "~" + (fCnt - 1) + " 누락 (" + gapSize + "건)");
                    send("missing", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));

                } else {                                // 갭이 작으면 빠진 fCnt마다 개별 신고
                    for (long missingFcnt = last + 1; missingFcnt < fCnt; missingFcnt++) {
                        log.warn("[결측] {} fCnt {} 누락", sensorReading.deviceEui(), missingFcnt);
                        QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.MISSING,
                                "결측: fCnt " + missingFcnt + " 누락");
                        send("missing", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));
                    }
                }
                // 개별/요약 신고와 무관하게 기준값은 항상 갱신
                lastFcnt.put(eui, fCnt);
                resetCandidate.invalidate(eui);

            } else {                        // ⑤ fCnt < last - 순서역전인지, 재조인(리셋)인지 후보로 확인
                Long candidate = resetCandidate.getIfPresent(eui);
                if (candidate != null && fCnt == candidate) {               // 같은 프레임의 다른 항목

                } else if (candidate != null && fCnt == candidate + 1) {    // 재조인(리셋) 확정
                    log.info("[fCnt리셋] {} 재조인 관측 (fCnt {} -> {} 연속)", eui, candidate, fCnt);
                    lastFcnt.put(eui, fCnt);
                    resetCandidate.invalidate(eui);
                } else {                // 새로 의심 등록
                    log.warn("[순서역전] {} (fCnt {} 도착, 최신 {})", eui, fCnt, last);
                    resetCandidate.put(eui, fCnt);
                }
            }
        }

        //지연 판정
        if(gap.getSeconds() > 60){

            log.info("[지연] {}:{} {}초",
                    sensorReading.deviceEui(), sensorReading.measurement(), gap.getSeconds());

            send("out", message.withEntry("_delayed",true));
            QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.DELAYED, "지연: " + gap.getSeconds() + "초");
            send("delayed", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));
        }
        else {
            send("out", message);
        }
    }
}
