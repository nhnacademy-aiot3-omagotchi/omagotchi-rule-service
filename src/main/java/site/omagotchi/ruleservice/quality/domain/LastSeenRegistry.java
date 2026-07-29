package site.omagotchi.ruleservice.quality.domain;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LastSeenRegistry {

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public void update(String deviceEui, String measurement, Instant when){
        lastSeen.put(key(deviceEui,measurement),when);
    }

    public Optional<Instant> lastSeenAt(String deviceEui, String measurement){
        return Optional.ofNullable(lastSeen.get(key(deviceEui, measurement)));
    }

    private String key(String deviceEui, String measurement){
        return deviceEui+":"+measurement;
    }
}
