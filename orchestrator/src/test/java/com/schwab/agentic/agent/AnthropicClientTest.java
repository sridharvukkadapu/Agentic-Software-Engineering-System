package com.schwab.agentic.agent;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

/**
 * Covers {@link AnthropicClient}: text extraction across multiple content blocks
 * (AC-03-6), API key redaction (AC-03-8), and the required live-API test (AC-03-1),
 * which checks {@code ANTHROPIC_API_KEY} itself and passes trivially (no assertion made)
 * when the key is absent, since the hand-rolled TestRunner has no separate "skipped"
 * status. When the key is present, this is the one test in the whole suite that makes a
 * real network call.
 */
public class AnthropicClientTest {

    public void testExtractTextConcatenatesAllTextBlocksNotJustTheFirst() {
        List<Object> blocks = List.of(
            Map.of("type", "text", "text", "First part. "),
            Map.of("type", "tool_use", "id", "irrelevant"),
            Map.of("type", "text", "text", "Second part."));

        String text = AnthropicClient.extractText(blocks);

        assertEquals("First part. Second part.", text,
            "extractText must concatenate every type==text block, skipping non-text blocks, not just index 0");
    }

    public void testExtractTextReturnsEmptyStringForNullOrNoTextBlocks() {
        assertEquals("", AnthropicClient.extractText(null), "null content must yield an empty string, not throw");
        assertEquals("", AnthropicClient.extractText(List.of(Map.of("type", "tool_use", "id", "x"))),
            "content with no text blocks must yield an empty string");
    }

    public void testConstructorRejectsABlankApiKey() {
        assertThrows(IllegalArgumentException.class,
            () -> new AnthropicClient(""),
            "AnthropicClient must reject a blank API key rather than silently accepting it");
        assertThrows(IllegalArgumentException.class,
            () -> new AnthropicClient(null),
            "AnthropicClient must reject a null API key rather than silently accepting it");
    }

    /**
     * Required test (AC-03-1): a live call against the real API returns text. Skipped
     * automatically (passes trivially) when ANTHROPIC_API_KEY is not set, since this
     * suite must run with zero network access and no key by default (CLAUDE.md).
     */
    public void testLiveCallAgainstTheRealApiReturnsText() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }

        AnthropicClient client = new AnthropicClient(apiKey);
        AgentResponse response = client.call(new AgentRequest(
            "You are a helpful assistant. Respond with exactly one short sentence.",
            "Say hello.",
            50,
            "live-test-node"));

        assertFalse(response.text().isBlank(), "a live call must return non-blank text");
        assertEquals(Mode.LIVE, response.mode(), "a real network call must report Mode.LIVE");
        assertTrue(response.inputTokens() > 0, "a real call must report a positive input token count");
        assertTrue(response.outputTokens() > 0, "a real call must report a positive output token count");
    }

    /**
     * The API key must never appear in any exception message this client produces
     * (AC-03-8). Exercised against a real local HTTP server (com.sun.net.httpserver,
     * part of the JDK, no external dependency) that returns a 401 whose body echoes the
     * key back, exactly the kind of error body a real API gateway might produce, so this
     * proves the client's own redaction actually runs on a real HTTP response rather
     * than asserting something true by construction.
     */
    public void testApiKeyIsNeverPresentInAnExceptionMessage() throws Exception {
        String fakeApiKey = "sk-ant-super-secret-test-key-do-not-leak";
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("localhost", 0), 0);
        try {
            server.createContext("/v1/messages", exchange -> {
                String body = "{\"error\": \"unauthorized, received key " + fakeApiKey + "\"}";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();

            String url = "http://localhost:" + server.getAddress().getPort() + "/v1/messages";
            AnthropicClient client = new AnthropicClient(fakeApiKey, url);

            AnthropicClient.AgentCallException thrown = assertThrows(AnthropicClient.AgentCallException.class,
                () -> client.call(new AgentRequest("system", "user", 100, "N1")),
                "a 401 response must be surfaced as AgentCallException");

            assertFalse(thrown.getMessage().contains(fakeApiKey),
                "the exception message must never contain the API key, even though the fake server's error body"
                    + " echoed it back: got \"" + thrown.getMessage() + "\"");
            assertTrue(thrown.getMessage().contains("[REDACTED]"),
                "the redacted exception message must show the redaction marker in place of the key");
            assertEquals(401, thrown.statusCode(), "the exception must carry the real status code");
        } finally {
            server.stop(0);
        }
    }
}
