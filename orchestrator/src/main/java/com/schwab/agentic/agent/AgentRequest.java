package com.schwab.agentic.agent;

/**
 * One call worth of instructions to an LLM: what role it should play
 * ({@code systemPrompt}), what it is actually being asked
 * ({@code userPrompt}), a ceiling on response length, and which node this call is for.
 *
 * {@code nodeId} exists purely for attribution: the AGENT_CALL audit event this request
 * eventually produces needs to say which node asked, and carrying it here means the
 * caller never has to thread it through separately or risk mismatching a response to the
 * wrong node's audit trail.
 */
public record AgentRequest(String systemPrompt, String userPrompt, int maxTokens, String nodeId) {
    public AgentRequest {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("AgentRequest systemPrompt must not be blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("AgentRequest userPrompt must not be blank");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("AgentRequest maxTokens must be at least 1, got " + maxTokens);
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("AgentRequest nodeId must not be blank");
        }
    }
}
