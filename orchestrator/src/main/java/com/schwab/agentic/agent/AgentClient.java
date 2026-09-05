package com.schwab.agentic.agent;

/**
 * What calls an LLM on behalf of a node. There are exactly two production
 * implementations: {@link AnthropicClient}, which makes one real HTTP call and never
 * retries internally, and {@link ReplayClient}, which serves a previously recorded
 * response and never touches the network. Retry belongs to the execution engine (spec
 * 02), not to this interface: the engine already re-runs a node's whole executor on exit
 * gate failure, and every one of those re-runs must show up as its own call here, so the
 * audit log actually reflects how many times the model was asked, not a number reduced
 * by a retry loop hidden inside this layer.
 */
public interface AgentClient {

    AgentResponse call(AgentRequest request);
}
