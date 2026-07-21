package site.omagotchi.ruleservice.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SensorReadingTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripsThroughJsonWithoutLoss() throws Exception {
        SensorReading reading = new SensorReading(
                "trace-1234",
                "실습실",
                "전방 우측",
                "24e124128c140101",
                "co2",
                650.0,
                Instant.parse("2026-07-07T03:34:10.456Z"),
                Instant.parse("2026-07-07T03:34:11.000Z"),
                "AM107-140101"
        );

        //직렬화
        String json = objectMapper.writeValueAsString(reading);
        //역직렬화
        SensorReading restored = objectMapper.readValue(json, SensorReading.class);

        assertThat(restored).isEqualTo(reading);
    }
}
