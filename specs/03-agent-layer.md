# Spec 03: Agent layer

## Context

This is the spec that answers the interview question "are your agents actually calling LLMs,
or are you mocking the agent responses?" The answer must be: real calls, with a recorded
replay mode so an evaluator without an API key gets an identical run.

## Scope

### 1. `agent/AgentClient.java`

Interface: `AgentResponse call(AgentRequest request)`.

`AgentRequest` carries: a system prompt, a user prompt, a model, a max token count, an
optional expected output schema, and a node id for attribution.

`AgentResponse` carries: the text, token counts in and out, latency, the mode that served it
(LIVE or REPLAY), and the fixture key.

### 2. `agent/AnthropicClient.java`

Real calls with `java.net.http.HttpClient`. No SDK, no Jackson.

- `POST https://api.anthropic.com/v1/messages`
- Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`
- Model `claude-sonnet-4-6`
- Body built with `Json.write`, response parsed with `Json.parse`, text pulled from the
  content blocks by filtering on `type == "text"` rather than indexing position zero.
- Retry on 429 and 5xx with exponential backoff and jitter, bounded. A 4xx other than 429 is
  not retried.
- Never log the API key. Redact it if a request is ever dumped.

### 3. `agent/RecordingClient.java`

Decorator. Wraps a real client, writes every request/response pair to
`fixtures/<sha256-of-request-body>.json`. The fixture stores the full request alongside the
response so a reviewer can see exactly what was asked.

### 4. `agent/ReplayClient.java`

Serves from `fixtures/` by the same hash. A cache miss in replay mode **throws** with the
missing key and a hint to re-record. It must never silently fall back to a canned string:
that would be the mocking the interview asks about.

### 5. `agent/ResponseParser.java`

Agents return prose. Executors need structure.

- Extract fenced code blocks by language.
- Extract a JSON object from a response that may have prose around it.
- Validate the parsed object against a small expected-keys schema and return a typed failure
  when it does not match, so the node can retry with the parse error in context.

**A malformed agent response is a normal, expected event.** Handle it as a retryable failure
with the parse error fed back, never as a crash.

### 6. `agent/prompts/` templates

One file per stage, loaded from disk so prompts are reviewable and diffable as artifacts.
Each template takes a context map. Templates needed: `requirement`, `impact`, `design`,
`implement`, `test`, `document`.

Every prompt must instruct the model to return the structured part in a fenced block with a
declared language, so extraction is deterministic.

### 7. Mode selection

`--live` requires `ANTHROPIC_API_KEY` and wraps `AnthropicClient` in `RecordingClient`.
`--replay` (default) uses `ReplayClient`. Selection is logged as an audit event at run start
so a run report states which mode produced it.

## Acceptance criteria

- AC-03-1: A live call against the real API returns text. Skipped automatically when no key is set.
- AC-03-2: Live mode writes a fixture file whose name is the hash of the request body.
- AC-03-3: Replay mode with that fixture present returns the identical response with no network call.
- AC-03-4: Replay mode with a missing fixture throws naming the missing key.
- AC-03-5: Two replay runs of the same scenario produce identical artifacts, ignoring timestamps.
- AC-03-6: Response text extraction concatenates all `type == "text"` blocks, not just the first.
- AC-03-7: A response with no fenced block yields a typed parse failure, not an exception.
- AC-03-8: The API key never appears in any audit event, log line or artifact. Assert with a repo-wide scan of `runs/`.

## Out of scope

Executors that use these prompts. Spec 04.

## Verify

```bash
./scripts/test.sh
ANTHROPIC_API_KEY=... ./scripts/test.sh --live   # runs the live-only tests too
```
