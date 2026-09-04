package com.schwab.urlshortener.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
