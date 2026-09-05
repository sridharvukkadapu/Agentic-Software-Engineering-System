package com.schwab.agentic.agent;

/**
 * Which kind of client actually served a call: a real network request, or a previously
 * recorded fixture. Carried on every {@link AgentResponse} so a run report can state
 * plainly which mode produced it, per spec 03's requirement that mode selection is
 * logged and never left for a reader to guess from context.
 */
public enum Mode {
    LIVE,
    REPLAY
}
