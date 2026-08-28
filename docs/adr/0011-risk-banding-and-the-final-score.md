# ADR-0011 — How a rule score and a model score become one number, and what bands it

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0008](0008-scoring-service-boundary.md),
  [ADR-0010](0010-model-selection-and-evaluation.md)

## Context

Two of the three numbers on a risk assessment now exist. The ruleset in `apps/api` produces a rule
score (PR [#44](https://github.com/la3679/sentinelflow/pull/44)) and the scoring service produces a
model score (PR [#43](https://github.com/la3679/sentinelflow/pull/43)). The third — `final_score`,
the one a band and eventually an alert are derived from — has no definition anywhere, and
`risk_assessments` has had a column waiting for it since Phase 2.

[ADR-0008](0008-scoring-service-boundary.md) §4 settled **who** owns it: the API, as versioned
configuration, on a different schedule from the model. It deliberately did not settle **what** the
combination is, and writing `RiskAssessmentService` would decide it in a line of arithmetic nobody
reviewed.

Three things make it worth deciding in the open:

1. **A degraded assessment and a scored one must be comparable.** ADR-0008 §4 requires one threshold
   to mean the same thing whether or not the model answered. A combination that makes a rules-only
   score systematically smaller than a mixed one would band identical transactions differently
   depending on whether a container happened to be up.
2. **The rule score is the only part an analyst can check.** A combination that lets a confident
   model suppress a rule an analyst would act on is one that cannot be defended in a review.
3. **Bands are what the console shows and what alerting will use.** Getting them wrong is not a
   presentation problem; it is every downstream decision.

## Decision

### 1. The final score is a weighted mean, floored by the rule score

```text
scored:   final = max(rule, modelWeight x model + (1 - modelWeight) x rule)
degraded: final = rule
```

`modelWeight` is configuration, versioned as `policyVersion`, and defaults to **0.6**.

**The weighted part is why the model is here at all.** The model finds shapes the rules miss — that
is the whole of ADR-0010 §5's margin, measured at 0.83 PR-AUC against the ruleset's 0.26 — and a
combination that ignored it would make the model an expensive decoration.

**The floor is why the rules are here at all.** Without it, a model scoring 0 on a transaction that
tripped three rules produces a final score of 0.4 x the rule score, and a transparent indicator an
analyst would act on is diluted by a number they cannot inspect. A rule that fires is a rule that
fires. The floor costs nothing when the model agrees and is the whole point when it does not.

**Rejected: a plain weighted mean.** It has the dilution problem above, and it makes a degraded
assessment score _higher_ than a scored one for the same transaction whenever the model is
unconvinced — so "the model came back and the score went down" would be a routine event that looks
exactly like a bug.

**Rejected: `max(rule, model)`.** It throws away _how far_ above the rules the model is: a model at
95 and a model at 71 both land at their own value, so the rules stop having any say once the model
leads. Under the weighted form they land at 81 and 66.6 against a rule score of 60 — the model leads
and the rules still temper it.

**What this combination does not do, stated plainly.** Because of the floor, a model score at or
below the rule score changes nothing: `combine(60, 60)` and `combine(60, 0)` are both 60. So this is
**not** a scheme that rewards corroboration — two signals agreeing land where one would. That was an
assumption in the first draft of this ADR and a test written against it failed, which is how it was
caught.

It is left that way deliberately. Counting agreement twice would make the same evidence raise a score
because two components observed it, and the model's features and the rules' indicators are computed
from the same account context — they are not independent, so treating them as corroborating would be
double-counting one observation. **The rules set a floor and the model escalates above it.** A model
can raise a score and never lower one, which is the property an analyst can be told in one sentence.

**Rejected: a learned combination.** It would need labels for the combination itself, which makes
`policyVersion` a model version and puts a business decision back on a retraining schedule — exactly
what ADR-0008 §4 separates.

### 2. A degraded assessment uses the rule score unchanged, and says so

Not the rule score scaled up to compensate for the missing model. Scaling would be inventing the
model's opinion, and the number would then be neither the rules' answer nor anybody's.

`degraded = true` is persisted and is **never inferred from a null model score**. The console renders
it as "scored by rules, model unavailable" (ADR-0008 §2). The two are comparable because both are on
the same 0-to-100 scale and both are floored by the same rule score; they are not identical, and
pretending otherwise is what the flag prevents.

### 3. Bands are `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`, from configured lower bounds

| Band       | Default lower bound |
| ---------- | ------------------- |
| `LOW`      | 0                   |
| `MEDIUM`   | 40                  |
| `HIGH`     | 70                  |
| `CRITICAL` | 90                  |

Inclusive lower bounds, ascending, validated at startup: strictly increasing, starting at zero, and
within the contract's scale. A gap or an inversion is refused rather than clamped, because a band
table that silently reorders itself produces assessments nobody can explain.

**These numbers are a starting point, not a measurement.** The model's own operating point sits at
99.9998 on this scale ([`EVALUATION.md`](../ml/EVALUATION.md)), so almost nothing scores between 40
and 90 today and `CRITICAL` is where the interesting traffic lands. That is a property of a
saturated score distribution rather than of the bands, it is recorded in the evaluation document's
limitations, and it is a reason to revisit the thresholds against measured alert volume in Phase 5 —
not a reason to pick different arbitrary numbers now.

### 4. A transaction that trips no rule cannot open an alert, and that is the intended reading

This follows from §1 and the alerting threshold together, and it was not visible until they met. It
is written down here because it is a policy, and a policy nobody stated is an accident.

The floor caps what the model alone can reach. With a rule score of zero:

```text
final = max(0, 0.6 x model + 0.4 x 0) = 0.6 x model
```

so a model at its ceiling of 100 produces **60**, and `HIGH` starts at 70. **No transaction that
trips no transparent indicator can open an alert, however confident the model is.** With the model
at its ceiling the rule score has to reach 25 before the combination clears 70, and 25 is the weight
of the single strongest indicator in today's ruleset — so in practice at least one rule an analyst
can read has to have fired, and the weakest ones have to have fired alongside something else.

**This is the intended behaviour, not a defect to route around.** SentinelFlow's console is
explainability-first: an alert is a claim on a person's time, and every alert it raises can be opened
on a reason an analyst can check and disagree with. An alert whose entire justification is "the model
was confident" is the one an analyst cannot review, cannot dispute, and learns to clear without
reading. Requiring a transparent indicator to have fired is what §1's floor buys, and this is where
it is spent.

**What it costs, stated plainly.** The model's whole value under ADR-0010 §5 is the shapes the rules
miss, and this bounds that value: a transaction the model recognises and the ruleset does not is
scored, banded, persisted, visible in the console and searchable — and it opens nothing. That is a
real cost and it is accepted with the reasoning above, not overlooked.

**Deliberately not resolved by moving a number.** Lowering `alertFromBand` to `MEDIUM`, raising
`modelWeight`, or removing the floor would each dissolve it, and each would be re-deciding §1 or §3
without the evidence those sections already say they should be revisited against — measured alert
volume against a stated review capacity. `sentinelflow.alerts.raised` is tagged by band and priority
so that evidence can exist. Until it does, the arithmetic stays as it is.

`RiskPolicyPropertiesTests` asserts both halves — that a maximal model over a silent ruleset bands
below the alerting threshold, and that 25 is where the combination first clears it — so a later
change to the weight or the threshold cannot alter this implication silently.

### 5. `policyVersion` moves whenever any of this does

The weight, the thresholds, and the band mapping are one versioned object. It is persisted on every
assessment beside `modelVersion` and `featureVersion`, because "which policy produced this alert" has
to have an answer independent of "which model", and an assessment that cannot name all three cannot
be defended months later.

## Consequences

- `RiskAssessmentService` has one arithmetic definition, in one place, with the reasoning above
  beside it rather than in a commit message.
- A degraded assessment is a defensible answer rather than a null, and the difference between the two
  paths is one term in one expression.
- Alert creation in Phase 5 attaches to a band that already exists and is already persisted; it does
  not have to reopen any of this.
- Alerting is gated on the rules, by arithmetic rather than by a condition anybody wrote: the model
  escalates a transaction the rules already noticed, and never opens an alert on its own (§4). The
  ceiling that produces it is `modelWeight x 100`, so it moves whenever the weight does.
- The default thresholds will almost certainly move once alert volume is measured against a review
  capacity. That is what `policyVersion` is for, and every assessment written under the old numbers
  keeps saying so.

**Revisit if:** measured alert volume at these bands does not match a stated review capacity; the
score distribution stops being saturated (a recalibrated or differently-shaped model would do it); a
second consumer of `finalScore` appears whose needs differ from alerting's; or the model is shown to
find fraud the ruleset misses often enough that §4's cost outweighs what the floor buys — in which
case the answer is a new rule that makes the shape transparent, or a superseding ADR that says
plainly that alerts may rest on the model alone.
