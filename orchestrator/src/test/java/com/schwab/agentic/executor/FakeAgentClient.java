package com.schwab.agentic.executor;

import com.schwab.agentic.agent.AgentClient;
import com.schwab.agentic.agent.AgentRequest;
import com.schwab.agentic.agent.AgentResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A test-only {@link AgentClient} standing in for the network, mirroring
 * {@code com.schwab.agentic.agent}'s own test double of the same name and purpose. Used
 * here to produce real fixtures via a real {@code RecordingClient} without a live API
 * key or a real network call, per the placeholder-fixture approach documented in
 * docs/decisions.md while the configured account has no credit balance.
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

    static FakeAgentClient alwaysReturningText(String text) {
        return alwaysReturning(new AgentResponse(text, 100, 200, 500,
            com.schwab.agentic.agent.Mode.LIVE, "unused-fake-fixture-key"));
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
