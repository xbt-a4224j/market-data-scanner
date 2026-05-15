# ADR-003: Composite scoring — confidence-weighted average + calibration

**Status:** Accepted

## Context

The scanner runs N parallel heuristics per pool. Each returns a `HeuristicResult` with:

- `score` ∈ [0.0, 1.0] — how risky this heuristic thinks the token is
- `confidence` ∈ [0.0, 1.0] — how much it trusts its own score
- `evidence` — JSONB blob for the dashboard to render

The composite scorer combines these into one [0, 100] integer that drives the live feed, the admin review threshold, and the backtest precision/recall.

The naive options were:

1. **Simple weighted average** of `score` across heuristics: `Σ(w_i × s_i) / Σ(w_i)`.
2. **Maximum** across heuristics: "if any one heuristic flags it high, treat as high-risk".
3. **Voting** with hard thresholds: count how many heuristics fire `score >= 0.7`.

## Decision

**Confidence-weighted average:**

```
numerator   = Σ over heuristics of (weight_i × confidence_i × score_i)
denominator = Σ over heuristics of (weight_i × confidence_i)
composite   = round(numerator / denominator × 100)
```

When the denominator is zero (every heuristic returned `confidence = 0`), the composite falls back to a `NEUTRAL_COMPOSITE = 50` constant rather than dividing by zero.

Weights live in `application.yml` under `heuristics.weights.*` and are bound via a `@ConfigurationProperties` data class. A heuristic with no weight entry counts as weight zero — it runs and produces evidence for the dashboard but does not influence the composite. This is how new heuristics ship dark before being wired into the scoring path.

## Calibration

Initial weights:

| Heuristic | Weight | Notes |
| --- | --- | --- |
| `supply_concentration` | 0.20 | implemented |
| `lp_lock`              | 0.20 | implemented |
| `deployer_history`     | 0.30 | implemented (the strongest single signal) |
| `metadata_similarity`  | 0.15 | implemented (#19) |
| `funding_flow`         | 0.0  | stub |
| `source_code_pattern`  | 0.0  | stub |
| `first_n_buyers`       | 0.0  | stub |
| `social_media_correlation` | 0.0 | stub |

`heuristics.thresholds.high_risk = 70` is the cutoff above which detections route into the admin review queue. `heuristics.thresholds.high_confidence = 0.85` is the per-heuristic confidence floor for a "decisive" alert.

## Consequences

**Positive:**

- A stub heuristic returning `confidence = 0` contributes **nothing** to the composite — neither numerator nor denominator. A naive weighted average would have dragged the composite toward 0.5 instead. This means we can ship eight heuristics with five stubs and still have a meaningful composite from the three real ones.
- A heuristic that throws falls back to `confidence = 0` (caught by `runCatching` in the scorer), so one degraded heuristic never crashes the whole composite.
- The "weight 0 = run dark" trick lets us add new heuristics in production without touching any existing tested behavior.

**Negative:**

- Confidence is heuristic-self-reported. There is no orthogonal calibration signal that says "this heuristic's confidence is empirically 0.85 on backtest". The next iteration should learn confidence from the ground-truth label set instead of hardcoding 0.85.
- The thresholds (`high_risk = 70`, `high_confidence = 0.85`) are picked, not learned. The backtest harness asserts precision > 0.85 and recall > 0.80 on the seed corpus, but the corpus is small (142 + 142). Real calibration needs orders of magnitude more labeled data.

## Alternatives considered

- **Maximum**: rejected because a single high-confidence false-positive heuristic could flag every detection.
- **Voting**: rejected because it loses information — the difference between `composite = 71` and `composite = 95` is meaningful for triage; binary voting collapses both to "flagged".
- **Learned weights** (logistic regression over heuristic outputs against labeled data): correct long-term path. Deferred until the labeled corpus is large enough to fit a model that does not just memorize the training set.
