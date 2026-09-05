package com.schwab.agentic.agent;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertTrue;

import java.util.List;

/**
 * Covers {@link ResponseParser}: extracting fenced blocks, extracting and validating
 * JSON from a response that may have prose around it, and the required test that
 * malformed JSON produces a typed parse failure rather than a partial or salvaged
 * object.
 */
public class ResponseParserTest {

    public void testExtractsAFencedBlockWithItsDeclaredLanguage() {
        String response = "Here is the analysis:\n\n```json\n{\"key\": \"value\"}\n```\n\nDone.";

        List<ResponseParser.CodeBlock> blocks = ResponseParser.extractFencedBlocks(response);

        assertEquals(1, blocks.size(), "expected exactly one fenced block");
        assertEquals("json", blocks.get(0).language(), "block language must be extracted");
        assertTrue(blocks.get(0).content().contains("\"key\": \"value\""), "block content must be extracted");
    }

    public void testExtractsMultipleFencedBlocksInOrder() {
        String response = "```java\ncode here\n```\nsome prose\n```json\n{\"a\": 1}\n```";

        List<ResponseParser.CodeBlock> blocks = ResponseParser.extractFencedBlocks(response);

        assertEquals(2, blocks.size(), "expected two fenced blocks");
        assertEquals("java", blocks.get(0).language(), "first block must be java");
        assertEquals("json", blocks.get(1).language(), "second block must be json");
    }

    public void testExtractJsonSucceedsWhenAllRequiredKeysArePresent() {
        String response = "Prose before.\n```json\n{\"normalizedProblem\": \"x\", \"ambiguities\": []}\n```\nProse after.";

        ResponseParser.ParseResult result = ResponseParser.extractJson(
            response, "json", List.of("normalizedProblem", "ambiguities"));

        assertTrue(!result.isFailure(), "extraction with all required keys present must succeed");
        assertEquals("x", result.value().get("normalizedProblem"), "parsed value must be readable");
    }

    /**
     * Required test: malformed JSON must produce a parse failure, not a partial object
     * assembled from whatever could be salvaged.
     */
    public void testMalformedJsonProducesAParseFailureNotAPartialObject() {
        String response = "```json\n{\"normalizedProblem\": \"x\", \"ambiguities\": [\n```";

        ResponseParser.ParseResult result = ResponseParser.extractJson(
            response, "json", List.of("normalizedProblem"));

        assertTrue(result.isFailure(), "truncated/malformed JSON must be reported as a failure");
        assertTrue(result.failure().reason().contains("not valid JSON"),
            "failure reason must say the block was not valid JSON: " + result.failure().reason());
        com.schwab.agentic.Assertions.assertThrows(IllegalStateException.class,
            result::value,
            "calling value() on a failed ParseResult must throw rather than return a partial or null object");
    }

    public void testMissingFencedBlockProducesATypedFailureNotAnException() {
        String response = "The model just wrote prose with no fenced block at all.";

        ResponseParser.ParseResult result = ResponseParser.extractJson(response, "json", List.of("key"));

        assertTrue(result.isFailure(), "a response with no fenced block must be a typed failure");
        assertTrue(result.failure().reason().contains("no fenced json block"),
            "failure reason must say no block was found: " + result.failure().reason());
    }

    public void testValidJsonMissingARequiredKeyProducesATypedFailure() {
        String response = "```json\n{\"normalizedProblem\": \"x\"}\n```";

        ResponseParser.ParseResult result = ResponseParser.extractJson(
            response, "json", List.of("normalizedProblem", "ambiguities"));

        assertTrue(result.isFailure(), "JSON missing a required key must be a typed failure, even though it parsed");
        assertTrue(result.failure().reason().contains("ambiguities"),
            "failure reason must name the missing key: " + result.failure().reason());
    }

    public void testJsonThatParsesToAnArrayNotAnObjectIsATypedFailure() {
        String response = "```json\n[1, 2, 3]\n```";

        ResponseParser.ParseResult result = ResponseParser.extractJson(response, "json", List.of());

        assertTrue(result.isFailure(), "a JSON array where an object was expected must be a typed failure");
    }
}
