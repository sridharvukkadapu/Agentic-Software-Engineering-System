package com.schwab.urlshortener.url;

/**
 * Encodes a positive database id as a base62 short code.
 *
 * Deriving the code from the id (rather than generating a random string and retrying on
 * collision) means codes are collision-free by construction and the encoding is a pure,
 * independently testable function.
 */
public final class ShortCodeEncoder {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private ShortCodeEncoder() {
    }

    public static String encode(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }
        StringBuilder result = new StringBuilder();
        long remaining = id;
        while (remaining > 0) {
            int digit = (int) (remaining % BASE);
            result.append(ALPHABET.charAt(digit));
            remaining /= BASE;
        }
        return result.reverse().toString();
    }
}
