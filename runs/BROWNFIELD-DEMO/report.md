# Run report: BROWNFIELD-DEMO

## Metrics

| Metric | Value |
|---|---|
| Success rate | 0.0% |
| Total retries | 1 |
| Retry frequency (per attempted node) | 1.00 |
| Rollback count | 0 |
| Rollback frequency (per node) | 0.00 |
| End-to-end latency | 0.251s |
| MTTR | null (no node needed more than one attempt) |
| Unrecovered nodes (failed, never later completed) | REQUIREMENT |

## Workflow graph

```mermaid
flowchart TD
    REQUIREMENT["REQUIREMENT (FAILED)"]
    IMPACT["IMPACT (PENDING)"]
    DESIGN["DESIGN (PENDING)"]
    TEST["TEST (PENDING)"]
    IMPLEMENT["IMPLEMENT (PENDING)"]
    DOCUMENT["DOCUMENT (PENDING)"]
    VALIDATE["VALIDATE (PENDING)"]
    RELEASE["RELEASE (PENDING)"]
    DESIGN --> TEST
    DESIGN --> IMPLEMENT
    REQUIREMENT --> IMPACT
    IMPLEMENT --> VALIDATE
    TEST --> VALIDATE
    DOCUMENT --> VALIDATE
    VALIDATE --> RELEASE
    DESIGN --> DOCUMENT
    IMPACT --> DESIGN
```

## Traceability matrix

| Criterion | Evidence | Origin | Passed | Artifact |
|---|---|---|---|---|
