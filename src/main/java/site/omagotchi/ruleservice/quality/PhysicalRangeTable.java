package site.omagotchi.ruleservice.quality;

import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
public class PhysicalRangeTable {
    private final Map<String, PhysicalRange> ranges;

    public Optional<PhysicalRange> rangeOf(String measurement){
        return Optional.ofNullable(ranges.get(measurement));
    }
}
