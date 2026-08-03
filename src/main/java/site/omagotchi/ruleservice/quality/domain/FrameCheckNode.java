package site.omagotchi.ruleservice.quality.domain;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.inbound.domain.SensorReading;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프레임 도착 품질 검사. 값이 아니라 프레임 메타데이터(fCnt·시각)만 본다.
 * 중복(DUPLICATE)·지연(DELAYED)·결측(MISSING)은 이벤트로 발행하고,
 * 순서역전·fCnt리셋은 로그로만 남긴다.
 */
@Slf4j
public class FrameCheckNode extends AbstractNode {

    private final Cache<String,Boolean> seen = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100000)
            .build();

    // eui:measurement -> 마지막 fCnt (결측·순서역전 감지용)
    private final Map<String, Long> lastFcntMap = new ConcurrentHashMap<>();

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
        if (sensorReading.fCnt() == null) {
            return;
        }
        long fCnt = sensorReading.fCnt();
        String fk = sensorReading.deviceEui() + ":" + sensorReading.measurement();
        Long last = lastFcntMap.get(fk);

        if (last == null) {
            lastFcntMap.put(fk, fCnt);

        } else if (fCnt > last + 1) {
        for (long missingFcnt = last + 1; missingFcnt < fCnt; missingFcnt++) {
            log.warn("[결측] {}:{} fCnt {} 누락", sensorReading.deviceEui(), sensorReading.measurement(), missingFcnt);

            QualityEvent event = QualityEvent.from(sensorReading, QualityEvent.Type.MISSING,
                    "결측: fCnt " + missingFcnt + " 누락");
            send("missing", Message.of(sensorReading.traceId(), Map.of("qualityEvent", event)));
        }
        lastFcntMap.put(fk, fCnt);

        } else if (fCnt < last) {
            log.warn("[순서역전] {}:{} (fCnt {} 도착, 최신 {})", sensorReading.deviceEui(), sensorReading.measurement(), fCnt, last);
            // 최신 값 유지 - 갱신하지 않음

        } else {
            // 정상 증가
            lastFcntMap.put(fk, fCnt);
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
