package com.schwab.agentic.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A test-only {@link AgentClient} standing in for a real network call, so
 * {@link RecordingClient} and {@link AuditingAgentClient} can be tested against a
 * controllable response without ever touching the Anthropic API. This is legitimate test
 * double use, the same pattern as spec 02's {@code NoopExecutor} and
 * {@code ControllableExecutor}: it replaces the network, not the orchestrator's own
 * decision-making, and no production code path can ever reach it.
 */
final class FakeAgentClient implements AgentClient {

    private final Function<AgentRequest, AgentResponse> responder;
    private final List<AgentRequest> requestsSeen = new ArrayList<>();

    FakeAgentClient(Function<AgentRequest, AgentResponse> responder) {
        this.responder = responder;
    }

    static FakeAgentClient alwaysReturning(AgentResponse response) {
        return new FakeAgentClient(request -> response);
    }

    static FakeAgentClient throwing(RuntimeException exception) {
        return new FakeAgentClient(request -> {
            throw exception;
        });
    }

    @Override
    public AgentResponse call(AgentRequest request) {
        requestsSeen.add(request);
        return responder.apply(request);
    }

    List<AgentRequest> requestsSeen() {
        return List.copyOf(requestsSeen);
    }
}
