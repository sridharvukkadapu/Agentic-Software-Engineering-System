package com.schwab.agentic.agent;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers the record-then-replay pipeline end to end: a fixture written by
 * {@link RecordingClient} (wrapping a {@link FakeAgentClient} standing in for the real
 * network) is then read back by {@link ReplayClient} pointed at the same directory, and
 * the two responses must be identical. This proves the whole pipeline works without
 * hand-authored fixture JSON that could silently drift from what recording actually
 * produces, and without a live API key.
 */
public class RecordingAndReplayTest {

    public void testARecordedFixtureReplaysByteIdentical() throws IOException {
        Path fixturesDir = Files.createTempDirectory("agent-fixtures");
        AgentRequest request = new AgentRequest("You are a helpful assistant.", "Say hello.", 100, "N1");
        AgentResponse original = new AgentResponse("Hello there.", 12, 4, 250, Mode.LIVE, "unused-in-fake");

        RecordingClient recordingClient = new RecordingClient(FakeAgentClient.alwaysReturning(original), fixturesDir);
        AgentResponse recorded = recordingClient.call(request);

        ReplayClient replayClient = new ReplayClient(fixturesDir);
        AgentResponse replayed = replayClient.call(request);

        assertEquals(recorded.text(), replayed.text(), "replayed text must match exactly what was recorded");
        assertEquals(recorded.inputTokens(), replayed.inputTokens(), "replayed inputTokens must match exactly");
        assertEquals(recorded.outputTokens(), replayed.outputTokens(), "replayed outputTokens must match exactly");
        assertEquals(recorded.latencyMillis(), replayed.latencyMillis(), "replayed latencyMillis must match exactly");
        assertEquals(Mode.REPLAY, replayed.mode(), "a response served from a fixture must report Mode.REPLAY");
    }

    public void testTwoReplaysOfTheSameFixtureProduceIdenticalResponses() throws IOException {
        Path fixturesDir = Files.createTempDirectory("agent-fixtures");
        AgentRequest request = new AgentRequest("system", "user prompt", 50, "N1");
        AgentResponse original = new AgentResponse("deterministic text", 5, 5, 100, Mode.LIVE, "unused");

        new RecordingClient(FakeAgentClient.alwaysReturning(original), fixturesDir).call(request);

        ReplayClient replayClient = new ReplayClient(fixturesDir);
        AgentResponse first = replayClient.call(request);
        AgentResponse second = replayClient.call(request);

        assertEquals(first.text(), second.text(), "two replays of the same fixture must return identical text");
        assertEquals(first.fixtureKey(), second.fixtureKey(), "two replays of the same fixture must resolve the same key");
    }

    /**
     * Required test: replay mode with no fixture must throw, naming the missing hash,
     * never silently fall through to any kind of canned response.
     */
    public void testReplayWithAMissingFixtureThrowsNamingThePromptHash() throws IOException {
        Path fixturesDir = Files.createTempDirectory("agent-fixtures");
        AgentRequest request = new AgentRequest("system prompt never recorded", "user prompt never recorded", 100, "N1");

        String expectedHash = ReplayClient.hashOfRequest(request);

        ReplayClient replayClient = new ReplayClient(fixturesDir);
        ReplayClient.MissingFixtureException thrown = assertThrows(ReplayClient.MissingFixtureException.class,
            () -> replayClient.call(request),
            "replay with no matching fixture on disk must throw MissingFixtureException");
        assertTrue(thrown.getMessage().contains(expectedHash),
            "the exception message must name the specific missing hash: " + thrown.getMessage());
    }

    public void testDifferentRequestsProduceDifferentFixtureFiles() throws IOException {
        Path fixturesDir = Files.createTempDirectory("agent-fixtures");
        AgentRequest requestA = new AgentRequest("system", "prompt A", 100, "N1");
        AgentRequest requestB = new AgentRequest("system", "prompt B", 100, "N1");

        new RecordingClient(FakeAgentClient.alwaysReturning(
            new AgentResponse("response A", 1, 1, 1, Mode.LIVE, "unused")), fixturesDir).call(requestA);
        new RecordingClient(FakeAgentClient.alwaysReturning(
            new AgentResponse("response B", 1, 1, 1, Mode.LIVE, "unused")), fixturesDir).call(requestB);

        ReplayClient replayClient = new ReplayClient(fixturesDir);
        assertEquals("response A", replayClient.call(requestA).text(), "request A must replay its own recorded response");
        assertEquals("response B", replayClient.call(requestB).text(), "request B must replay its own recorded response");
    }

    public void testRecordingClientWritesAFixtureFileNamedByTheRequestHash() throws IOException {
        Path fixturesDir = Files.createTempDirectory("agent-fixtures");
        AgentRequest request = new AgentRequest("system", "user prompt", 100, "N1");
        AgentResponse response = new AgentResponse("text", 1, 1, 1, Mode.LIVE, "unused");

        new RecordingClient(FakeAgentClient.alwaysReturning(response), fixturesDir).call(request);

        String expectedHash = ReplayClient.hashOfRequest(request);
        Path expectedFile = fixturesDir.resolve(expectedHash + ".json");
        assertTrue(Files.exists(expectedFile), "expected fixture file at " + expectedFile + " to exist");
    }
}
