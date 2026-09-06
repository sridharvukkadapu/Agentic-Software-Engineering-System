# Run report: AMBIGUOUS-DEMO

## Metrics

| Metric | Value |
|---|---|
| Success rate | 0.0% |
| Total retries | 1 |
| Retry frequency (per attempted node) | 1.00 |
| Rollback count | 0 |
| Rollback frequency (per node) | 0.00 |
| End-to-end latency | 0.232s |
| MTTR | null (no node needed more than one attempt) |
| Unrecovered nodes (failed, never later completed) | REQUIREMENT |

## Workflow graph

```mermaid
flowchart TD
    REQUIREMENT["REQUIREMENT (FAILED)"]
    IMPACT["IMPACT (PENDING)"]
    DESIGN["DESIGN (PENDING)"]
    DOCUMENT["DOCUMENT (PENDING)"]
    IMPLEMENT["IMPLEMENT (PENDING)"]
    TEST["TEST (PENDING)"]
    VALIDATE["VALIDATE (PENDING)"]
    RELEASE["RELEASE (PENDING)"]
    IMPACT --> DESIGN
    DESIGN --> DOCUMENT
    VALIDATE --> RELEASE
    TEST --> VALIDATE
    IMPLEMENT --> VALIDATE
    DOCUMENT --> VALIDATE
    REQUIREMENT --> IMPACT
    DESIGN --> IMPLEMENT
    DESIGN --> TEST
```

## Traceability matrix

| Criterion | Evidence | Origin | Passed | Artifact |
|---|---|---|---|---|
