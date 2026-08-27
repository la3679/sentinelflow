# ADR-0010 — Model candidates, the label source, splitting, and how the shipped model is chosen

- **Status:** Accepted
- **Date:** 2026-08-26
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0004](0004-python-runtime-and-model-stack.md),
  [ADR-0008](0008-scoring-service-boundary.md)

## Context

The feature pipeline landed in PR [#34](https://github.com/la3679/sentinelflow/pull/34) and the
scoring contract in [#31](https://github.com/la3679/sentinelflow/pull/31). What is missing between
them is the model, and writing the training code first would silently decide four things that
deserve to be decided in the open — the same reason [ADR-0008](0008-scoring-service-boundary.md) was
written before either side of the boundary existed.

1. **Where do labels come from at all?** [`ScenarioType`](../../apps/api/src/main/java/io/github/la3679/sentinelflow/api/seed/scenario/ScenarioType.java)
   says, in its own Javadoc, that planted shapes **never enter the database**. So the operational
   schema cannot be the training source, and something else has to be.
2. **What is compared, and what is allowed to win?** §12.4 of the build prompt asks for four
   candidates. It does not say what happens when the flashiest one wins by a margin nobody would
   pay for.
3. **How is the data split?** Every planted shape is several correlated transactions on one
   account, which is a leak that a naive random split will not merely permit but actively reward.
4. **What scale is the score on?** [ADR-0008](0008-scoring-service-boundary.md) §4 gives the API a
   single alerting threshold that must apply identically to a model score and to a rules-only
   degraded score. That is a constraint on the model's output, and it rules one of the four
   candidates out of production before any of them is trained.

Two existing decisions bind this one. [ADR-0004](0004-python-runtime-and-model-stack.md) pins the
model stack to scikit-learn 1.9.0 with no gradient-boosting library, and
[ADR-0008](0008-scoring-service-boundary.md) §4 separates the model's **operating point** from the
API's **alerting policy**.

## Decision

### 1. Labels come from an export built on the runtime's own context assembler

The training dataset is produced by an **explicit offline export command in `apps/api`**, which runs
the existing [`ScenarioGenerator`](../../apps/api/src/main/java/io/github/la3679/sentinelflow/api/seed/scenario/ScenarioGenerator.java)
and writes one JSONL record per transaction: the **exact `ScoreRequest` body** the scoring service
would receive at runtime, plus the `ScenarioType` the generator planted it as. The label travels in
the export file and nowhere else. It does not enter `transactions`, and no migration adds a column
for it.

**The generator is not reimplemented in Python.** A second implementation of six transaction shapes
would drift from the first, and the drift would show up as a model that scores generated traffic
well and live traffic badly — the hardest class of defect to attribute, because both halves would
look correct in isolation.

**The export must call the same account-context assembler the consumer calls**, not a training-only
approximation of it. This is the load-bearing half of the decision. The context is the bounded
history [ADR-0008](0008-scoring-service-boundary.md) fixes as what crosses the boundary, and every
one of the sixteen features is computed from it; a training-time assembler that windowed, ordered,
capped or truncated differently from the runtime one would produce train/serve skew that no metric
in the evaluation report can detect, because both sides of the comparison would be drawn from the
training assembler.

**This reorders the remaining Phase 4 work, deliberately.** The context assembler was previously
scheduled with the Spring scoring client, after training. It moves ahead of training, and the client
consumes it rather than writing a second one. The alternative — train against an approximation now,
reconcile later — is how skew ships.

**The dataset is not committed.** It is regenerated from a recorded seed, generator version and
profile, and the training manifest stores a SHA-256 fingerprint over the export. A fingerprint that
does not match a rerun is the signal that a "reproducible" result was not.

### 2. Four candidates, one of which cannot be shipped under ADR-0008

| Candidate                        | Role                                     | Eligible to ship    |
| -------------------------------- | ---------------------------------------- | ------------------- |
| Transparent rules baseline       | The floor everything is measured against | yes — in `apps/api` |
| `LogisticRegression`             | Explainable supervised baseline          | yes                 |
| `HistGradientBoostingClassifier` | Tree-based, only if it materially helps  | yes                 |
| `IsolationForest`                | Unsupervised comparison                  | **no — see §4**     |

**The tree-based candidate is scikit-learn's, not LightGBM's or XGBoost's.**
[ADR-0004](0004-python-runtime-and-model-stack.md) pinned the stack against the intersection of
every package's declared Python support, and adding a gradient-boosting dependency to beat a
baseline that has not yet been measured would be adding a technology before demonstrating the need —
which the workflow rules forbid outright. If the measured gap turns out to justify one, it needs a
research entry and a lock change, not a quiet import.

**The rules baseline lives in `apps/api`**, per [ADR-0002](0002-monorepo-and-service-boundaries.md)
§3, and that placement is what makes a degraded assessment a real answer instead of a null with a
flag on it: when the scoring service is unreachable, the rules still run, in-process, and the
transaction still gets a score on the same scale.

### 3. The split is group-disjoint by account **and** time-ordered

Both properties, because neither one subsumes the other and each catches a different leak.

**Group-disjoint on `accountReference`.** An account is entirely in train or entirely in test, never
split across both. Every planted shape — a velocity burst, a card-testing run, a drain — is several
transactions on one account, and each of them becomes a labelled row whose features are computed
from the others. Splitting an account puts near-duplicates of a test row into training, and the
model is then rewarded for recognising a specific burst rather than the shape of bursts. Model
selection uses `StratifiedGroupKFold` grouped on the account reference, stratified because the
positive class is a small minority.

**Time-ordered final holdout.** The held-out evaluation set is drawn from the **later** part of the
generated window as well as from held-out accounts. Group-disjointness alone would happily train on
traffic from after the test period, which is a thing production can never do. The reported headline
metrics come from this holdout; the cross-validation folds are for choosing between candidates and
for choosing the operating point, and their numbers are reported as what they are.

**Seeds are fixed and recorded** — the generator's, NumPy's, and every estimator's `random_state` —
and stored in the training manifest beside the dataset fingerprint and the feature version.

### 4. The 0–100 score must be calibrated underneath, which is why Isolation Forest cannot ship

[ADR-0008](0008-scoring-service-boundary.md) §4 gives the API one threshold, applied to a final
score, which must mean the same thing whether the model answered or the rules answered alone. That
is only coherent if the scale is stable across model versions and shared with the rules baseline.

**The units are the contract's, not this ADR's.**
[`sentinelflow-scoring.yaml`](../../contracts/openapi/sentinelflow-scoring.yaml) already fixes
`modelScore` as a number from 0 to 100 and states, in the schema itself, that it is **not** a
probability — because calling it one invites "this transaction is 87% likely to be fraud", which no
model trained here supports. That is authoritative and this ADR does not reopen it.

**What is required is calibration, which is a property of the mapping and not of the units.** The
estimator is fitted to produce a well-calibrated probability of the positive class, calibration is
measured (Brier score and a reliability curve) rather than assumed, and the calibrated value is then
carried onto the contract's 0–100 scale. A calibrated quantity is still calibrated after a fixed
monotone rescaling, so there is no tension between the two requirements — only between "calibrated"
and the everyday reading of the word "probability", which is exactly what the contract's wording
guards against.

**The positive class is "belongs to a planted suspicious shape", not "is fraud".** Those are
different propositions, and the second is not something synthetic data can support a claim about.
This is the substantive reason the contract's caution is right, and the model card repeats it.

Without calibration underneath, a threshold of 80 would mean one thing under a logistic model,
another under a boosted one, and something unrelated under a rules-only degraded assessment — and
promoting a model would silently re-tune the business's alert volume with nobody changing the
policy.

**`IsolationForest` emits an unbounded, dataset-relative anomaly score with no calibrated mapping
onto any fixed scale.** It is kept as an unsupervised comparison because it answers a question worth
asking — how much of the planted structure is visible without labels at all — and it is excluded
from candidacy because giving it a stable 0–100 meaning requires calibrating against the labels it
was chosen for being able to ignore. Reporting it as a peer of the supervised candidates would be
comparing two different quantities that happen to be printed to the same number of decimal places.

### 5. The selection rule is fixed here, before any of it is measured

Written in advance precisely so the choice cannot be rationalised after the numbers are seen.

**PR-AUC (average precision) is the headline.** Accuracy is never reported as a headline anywhere,
in this repository or in its README: under this class imbalance a model that answers "not
suspicious" to everything scores extremely well on it, and that is the model this project exists to
argue against.

**A model ships only if it beats the rules baseline by a margin that is stated and defended.** If it
does not, **the rules baseline ships alone** and the model is recorded as evaluated and not
promoted. Having built a model is not a reason to serve one.

**Between eligible models, the simpler one wins ties**, and "ties" is defined before measurement: if
the tree-based model's holdout PR-AUC does not exceed logistic regression's by more than the spread
across the cross-validation folds, the difference is not distinguishable from fold noise and
logistic regression is selected — for its inference cost, its per-feature explainability, and the
fact that a coefficient can be shown to an analyst.

**The operating point is chosen against an alert-volume budget, not against F1.** The threshold is
the one whose alert volume matches a stated review capacity, expressed as a percentage of scored
transactions and recorded in the model card; precision, recall, false-positive rate and the
confusion matrix are then reported **at that point**. An analyst team is a fixed-capacity queue, and
a threshold picked by maximising F1 optimises an arithmetic property of the confusion matrix rather
than anything an operations team experiences. F1 and F-beta are reported beside it, and ROC-AUC only
as a secondary metric.

This operating point is the model's, and it is a **recommendation** — the API's alerting policy is a
separate, versioned object per [ADR-0008](0008-scoring-service-boundary.md) §4, and both are
persisted on every assessment so that which one actually ran is never inferred.

### 6. Training is an offline command; the artifact is committed and checksum-verified

**Never an API side effect**, per §12.6. `uv run python -m sentinelflow_scoring.training` writes a
registry entry under `apps/scoring/models/<model-name>/<version>/`: the joblib artifact, a
`manifest.json` (dataset fingerprint, feature version, split strategy, seeds, hyperparameters, the
resolved environment lock hash, artifact SHA-256), `metrics.json`, the plots, and the model card.

**The artifact is committed to the repository.** It is small, and the alternative is a demo that
cannot score anything until someone runs a training job — which for a portfolio project means a demo
that is never seen working. A size ceiling is enforced by the training command rather than left to
review, and the artifact is loaded only after its SHA-256 matches the manifest, per
[ADR-0004](0004-python-runtime-and-model-stack.md)'s security note.

**The checksum proves the loaded artifact is the evaluated artifact. It does not claim
byte-reproducible retraining** — joblib output depends on library build details that vary across
platforms, and claiming otherwise would be a reproducibility guarantee this project cannot honour.
What is reproducible, and what CI can check, is the **metrics** from a fixed seed and a fingerprinted
dataset.

**Promotion validates three things before an artifact becomes active**: the checksum, that the
artifact's feature version matches the running `FEATURE_VERSION`, and that the required metrics are
present. Rollback is selecting the previous registry directory; nothing is deleted on promotion.

## Alternatives considered

**Reimplementing the scenario generator in Python so training never depends on the Java side.**
Rejected: two definitions of six shapes drift, and the resulting train/serve gap is invisible to
every metric because both sides of the evaluation would come from the same wrong generator.

**Training against the seeded database and treating `analyst_feedback` as labels.** Rejected for v1.
Feedback is an analyst's verdict on transactions the system already alerted on, so it exists only
for transactions above the threshold — training on it learns the threshold, not the fraud. It is
stored for later experimentation, per §12.6, and is deliberately not a v1 label source.

**A random stratified split.** Rejected: it is not merely permissive here, it inflates every metric,
because the correlated rows of one planted shape land on both sides of the split and the model is
scored on recognising rows it has already seen most of.

**Time-only splitting, without account grouping.** Rejected: a shape that straddles the cut is
partly in each side. Group-only splitting, without the time constraint, was rejected for allowing
training on the test period's future.

**Letting the scoring service return a band instead of a score.** Already rejected in
[ADR-0008](0008-scoring-service-boundary.md); repeated here because the calibration requirement in
§4 is what makes that rejection implementable rather than merely stated.

**Serving the raw calibrated probability on the wire, in [0, 1].** Rejected, and the rejection is
the contract's rather than this ADR's: `modelScore` is already specified as 0 to 100 with an
explicit note that it is not a probability. An earlier draft of §4 said the service returns a
calibrated probability and was corrected against the contract before this ADR was merged —
recorded here because the correction is the point. A decision document that quietly contradicts an
authoritative contract is worse than one that never mentioned the scale, since it reads as
permission.

**Selecting the model by highest PR-AUC, full stop.** Rejected: it makes the tree-based model the
default outcome of the exercise regardless of margin, and it offers no answer at all to the case
where every model loses to the rules.

**Adding LightGBM or XGBoost.** Rejected for v1 as an unmeasured need against a pinned, justified
stack. Revisit with a research entry if the measured gap to `HistGradientBoostingClassifier` is
material.

## Consequences

**Positive.** Labels have exactly one home — an export file — and the schema stays free of a column
that would be the textbook leak. Training and serving compute their features from the same context
assembler, so the commonest ML production defect is prevented structurally rather than watched for.
The score is on a scale that the API's single threshold can be applied to honestly, whichever model
is active and even when none is. The selection rule, including the possibility that no model ships,
was fixed before any number existed.

**Negative.** The account-context assembler is now a Phase 4 prerequisite rather than part of the
client, which front-loads Java work into what looked like a Python step. Group-disjoint splitting
spends accounts rather than rows, so the effective dataset is smaller than the transaction count
suggests and the generated window has to be large enough to absorb that. A committed binary artifact
is a binary in Git, with the review friction that implies. And the calibration requirement adds a
step that a bare `predict_proba` would have skipped.

**Limitations, stated because the model card will repeat them.** Every metric this ADR governs is
measured on **synthetic data whose labels are the generator's own planted shapes** — so what is
being measured is the pipeline's ability to recover patterns that were deliberately put there, not
its ability to detect fraud. It is not a claim about real-world performance, it is not comparable to
any published fraud benchmark, and it does not reproduce any employer's reported figures.

**Revisit if:** a label source that is not the generator becomes available; the alert-volume budget
turns out to be the wrong operating-point rule once alerts exist and Phase 5 can measure queue
behaviour; or measured inference latency makes the calibrated model too slow for
[ADR-0008](0008-scoring-service-boundary.md) §3's budget, which Phase 9 measures.
