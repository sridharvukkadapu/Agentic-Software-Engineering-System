package com.schwab.agentic.agent;

import com.schwab.agentic.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an agent's free-form text response into structure an executor can act on.
 *
 * A malformed response, prose where JSON was expected, a missing fenced block, JSON
 * missing a required key, is a normal, expected event here, never a crash: every
 * extraction method returns a typed result the caller can inspect for failure and feed
 * back into the next retry attempt's context, rather than throwing past the caller. This
 * class also never salvages malformed output: if a fenced block is not valid JSON, that
 * is reported as a parse failure, not repaired or partially accepted, since a node
 * proceeding on an agent's malformed structure is exactly the kind of ungoverned trust
 * this whole project argues against.
 */
public final class ResponseParser {

    private static final Pattern FENCED_BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private ResponseParser() {
    }

    /** Every fenced code block in {@code text}, in order, each with its declared language (blank if none). */
    public static List<CodeBlock> extractFencedBlocks(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        Matcher matcher = FENCED_BLOCK.matcher(text);
        while (matcher.find()) {
            String language = matcher.group(1);
            String content = matcher.group(2);
            blocks.add(new CodeBlock(language == null ? "" : language, content));
        }
        return blocks;
    }

    /**
     * Extracts the first fenced block whose declared language is {@code language}
     * (case-insensitive), parses it as JSON, and checks that {@code requiredKeys} are
     * all present. Returns a {@link ParseResult} that is either a successful
     * {@code Map<String, Object>} or a {@link ParseFailure} naming exactly what went
     * wrong: no fenced block of that language, the block's content is not valid JSON, or
     * the parsed object is missing one or more required keys.
     *
     * Never throws for a malformed response: a caller (eventually a node executor, spec
     * 04) is expected to check {@link ParseResult#isFailure} and retry with the failure
     * reason in context rather than have this method throw past it.
     */
    @SuppressWarnings("unchecked")
    public static ParseResult extractJson(String text, String language, List<String> requiredKeys) {
        List<CodeBlock> blocks = extractFencedBlocks(text);
        CodeBlock jsonBlock = blocks.stream()
            .filter(block -> block.language().equalsIgnoreCase(language))
            .findFirst()
            .orElse(null);

        if (jsonBlock == null) {
            return ParseResult.failure(new ParseFailure(
                "no fenced " + language + " block found in the response", text));
        }

        Object parsed;
        try {
            parsed = Json.parse(jsonBlock.content().trim());
        } catch (RuntimeException e) {
            return ParseResult.failure(new ParseFailure(
                "fenced " + language + " block is not valid JSON: " + e.getMessage(), jsonBlock.content()));
        }

        if (!(parsed instanceof Map)) {
            return ParseResult.failure(new ParseFailure(
                "fenced " + language + " block parsed as JSON but is not an object", jsonBlock.content()));
        }

        Map<String, Object> parsedMap = (Map<String, Object>) parsed;
        List<String> missingKeys = requiredKeys.stream().filter(key -> !parsedMap.containsKey(key)).toList();
        if (!missingKeys.isEmpty()) {
            return ParseResult.failure(new ParseFailure(
                "parsed JSON is missing required keys: " + missingKeys, jsonBlock.content()));
        }

        return ParseResult.success(parsedMap);
    }

    /** One fenced code block: its declared language and its raw content. */
    public record CodeBlock(String language, String content) {
    }

    /** Why extracting structured JSON from a response failed, and the raw text that failed to parse. */
    public record ParseFailure(String reason, String rawContent) {
    }

    /**
     * Either a successfully parsed object, or a {@link ParseFailure} explaining why not.
     * Exactly one of {@link #value} or {@link #failure} is non-null, checked by
     * {@link #isFailure}, so a caller cannot accidentally read a null value as success.
     */
    public static final class ParseResult {
        private final Map<String, Object> value;
        private final ParseFailure failure;

        private ParseResult(Map<String, Object> value, ParseFailure failure) {
            this.value = value;
            this.failure = failure;
        }

        static ParseResult success(Map<String, Object> value) {
            return new ParseResult(value, null);
        }

        static ParseResult failure(ParseFailure failure) {
            return new ParseResult(null, failure);
        }

        public boolean isFailure() {
            return failure != null;
        }

        public Map<String, Object> value() {
            if (failure != null) {
                throw new IllegalStateException("ParseResult is a failure, call failure() instead: " + failure.reason());
            }
            return value;
        }

        public ParseFailure failure() {
            if (failure == null) {
                throw new IllegalStateException("ParseResult is a success, call value() instead");
            }
            return failure;
        }
    }
}
