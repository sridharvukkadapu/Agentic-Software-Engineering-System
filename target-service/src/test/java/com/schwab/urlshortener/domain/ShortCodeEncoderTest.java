package com.schwab.urlshortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("fast")
class ShortCodeEncoderTest {

    @Test
    void encodesSmallIdsToSingleCharacters() {
        assertThat(ShortCodeEncoder.encode(1)).isEqualTo("1");
        assertThat(ShortCodeEncoder.encode(10)).isEqualTo("A");
        assertThat(ShortCodeEncoder.encode(61)).isEqualTo("z");
    }

    @Test
    void encodesLargerIdsToMultipleCharacters() {
        assertThat(ShortCodeEncoder.encode(62)).isEqualTo("10");
        assertThat(ShortCodeEncoder.encode(3843)).isEqualTo("zz");
    }

    @Test
    void isCollisionFreeAcrossASequentialRange() {
        Set<String> codes = new HashSet<>();
        for (long id = 1; id <= 10_000; id++) {
            assertThat(codes.add(ShortCodeEncoder.encode(id)))
                .as("id %d produced a duplicate code", id)
                .isTrue();
        }
    }

    @Test
    void rejectsNonPositiveIds() {
        assertThatThrownBy(() -> ShortCodeEncoder.encode(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShortCodeEncoder.encode(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Round-trips encode/decode across a real sequence range, including the exact
     * digit-count boundaries (61/62, 3843/3844, and so on) where an off-by-one in either
     * direction most commonly hides, plus the two ends of the id space this encoding must
     * actually support: 1 (a fresh sequence's first value) and {@code Long.MAX_VALUE}.
     */
    @Test
    void roundTripsAcrossASequenceRangeIncludingBoundaries() {
        long[] boundaries = {
            1, 61, 62, 63,
            3843, 3844, 3845,
            238_327, 238_328, 238_329,
            Long.MAX_VALUE - 1, Long.MAX_VALUE
        };
        for (long id : boundaries) {
            String code = ShortCodeEncoder.encode(id);
            assertThat(ShortCodeEncoder.decode(code))
                .as("decode(encode(%d)) must return the original id", id)
                .isEqualTo(id);
        }
        for (long id = 1; id <= 10_000; id++) {
            assertThat(ShortCodeEncoder.decode(ShortCodeEncoder.encode(id)))
                .as("decode(encode(%d)) must return the original id", id)
                .isEqualTo(id);
        }
    }

    @Test
    void decodeRejectsACharacterOutsideTheAlphabet() {
        assertThatThrownBy(() -> ShortCodeEncoder.decode("has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShortCodeEncoder.decode("")).isInstanceOf(IllegalArgumentException.class);
    }
}
