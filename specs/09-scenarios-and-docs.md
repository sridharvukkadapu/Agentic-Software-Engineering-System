# Spec 09: Scenarios, documentation and demo runs

## Context

The final assembly. The assignment names three scenarios and five deliverables. This spec
turns a working system into a submission a reviewer can evaluate in fifteen minutes.

## Scope

### 1. The three scenarios

Each is a directory under `scenarios/` with `requirement.md`, a `scenario.json` declaring
mode and workflow, and a committed demo run under `runs/`.

**`greenfield`** - add a link-preview endpoint returning title and description for a short
code, with caching and a timeout.
Proves: decomposition from nothing, parallel fan-out, evidence generation.

**`brownfield`** - a deliberate regression is committed to the target service first: expiry
is checked after the click is recorded, so expired links still increment analytics. The
requirement is a bug report, not a solution.
Proves: codebase reasoning, impact analysis naming the right files before any change,
characterization of current behaviour, change budget policy, approval on a HIGH risk diff.

Commit the regression as its own commit, clearly labelled, so a reviewer sees the agent
found it rather than being handed it.

**`ambiguous`** - "popular short URLs should be faster without hammering the database."
No latency target, no definition of popular, no staleness budget, no statement about whether
shared cache infrastructure exists.
Proves: ambiguity detection, decision lineage, and re-planning. Declares
`amendAfterNode: DESIGN` with an amended requirement that adds a staleness constraint, so a
single command demonstrates the re-plan.

### 2. Documentation

**`README.md`** - the most important file in the repo. Structure:

1. What this is, in three sentences. Orchestration first, URL shortener second.
2. **A quickstart that works in one command with no API key.**
3. Architecture diagram.
4. The three scenarios with links to committed run reports.
5. How the twelve capabilities from section 4.4 map to specific files. A table with file
   paths. A reviewer should not have to hunt.
6. Live mode instructions.
7. Limitations, honestly.

The competing submission got asked "did you submit the agents, since you only see the URL
shortener application?" Make that question impossible.

**`docs/architecture.md`** - components, control flow, the graph model, the agent boundary,
governance, persistence and resume, re-planning. Include the key decisions with rejected
alternatives. Explain why the orchestrator is dependency-free and why agents propose while
deterministic code decides.

**`docs/testing.md`** - the layers, what is covered, what is not, why no JUnit in the
orchestrator, how replay makes runs reproducible.

**`docs/limitations.md`** - write this properly, it is scored. Real items: single-process
execution, no distributed locking, graph structure fixed at re-plan time, agent output
quality varies by model version, replay fixtures are point-in-time, no cost ceiling
enforcement, checkpoints are filesystem copies rather than a VCS-level mechanism.

**`docs/ai-assisted-development.md`** - how this repo was built with Claude Code:
spec-driven workflow, what was delegated, what was rejected and why, where the tool was
wrong. The job description asks for the ability to establish team-level practices, so
include the CLAUDE.md conventions and the spec template as reusable artifacts.

### 3. Demo runs committed to the repo

Run all three in replay mode and commit `runs/<runId>/` complete with report, audit log and
artifacts. A reviewer with no API key and no Docker reads these.

**Also commit one deliberately failing run** where a policy denial stops the workflow. A
governance layer never observed blocking anything is unproven governance, and this single
artifact answers the "is it real" question faster than any prose.

### 4. Final engineering summary

`docs/engineering-summary.md`, matching section 4.8: plan and rationale, artifacts
produced, risks and trade-offs, validation approach, assumptions, limitations.

Include the assumptions made about the assignment itself: that the target service is
hand-seeded so brownfield is possible, that replay mode is acceptable for evaluation, and
what was cut for time and why. Scope-cutting with stated rationale is engineering judgment,
which section 6 scores directly.

## Acceptance criteria

- AC-09-1: A fresh clone runs the quickstart with no API key, no Docker and no network, and produces a report.
- AC-09-2: The brownfield impact analysis names the file containing the regression before any change is made.
- AC-09-3: The ambiguous run's report shows at least two detected ambiguities and a completed re-plan.
- AC-09-4: The failing run's report shows a policy denial and a SAFE_STOPPED status.
- AC-09-5: Every one of the twelve section 4.4 capabilities maps to a real file path in the README table.
- AC-09-6: `docs/limitations.md` lists at least eight specific limitations, not generic hedging.
- AC-09-7: The README's first screen makes the orchestration layer, not the URL shortener, the subject.

## Verify

```bash
git clone <repo> fresh && cd fresh && ./scripts/quickstart.sh
```
