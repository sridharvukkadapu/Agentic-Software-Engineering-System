# Task specs

Sequenced work units for Claude Code. Each spec is self-contained: hand one to Claude Code
as a prompt, verify the acceptance criteria, commit, move to the next.

Run them in order. Specs 02-04 are the critical path; 07 can be done in parallel by a
second session since it touches a different directory.

| #  | Spec | Depends on | Rough effort |
|----|------|-----------|--------------|
| 01 | Orchestrator foundation: model, graph, state | - | 3h |
| 02 | Execution engine: scheduler, gates, retries, rollback | 01 | 4h |
| 03 | Agent layer: Anthropic client, record/replay, prompts | 01 | 3h |
| 04 | Node executors: the eight SDLC stages | 02, 03 | 6h |
| 05 | Policy engine, approvals, persistence and resume | 02 | 3h |
| 06 | Re-planning by graph reachability | 02 | 2h |
| 07 | Target service: Spring Boot URL shortener | - | 5h |
| 08 | Metrics, reporting and the run summary | 02, 05 | 2h |
| 09 | Three scenarios, docs, and recorded demo runs | all | 4h |

## How to use a spec with Claude Code

```
claude
> Read CLAUDE.md, then implement specs/02-execution-engine.md.
> Stop and show me the diff before committing.
```

Keep sessions scoped to one spec. When a spec is done, run its verify command, commit,
and start a fresh session for the next one. Long sessions drift.
