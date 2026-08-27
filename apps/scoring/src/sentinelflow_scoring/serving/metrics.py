"""Prometheus collectors for the request path.

Module level, and registered against the default registry exactly once. A
collector created inside :func:`sentinelflow_scoring.app.create_app` would raise
``Duplicated timeseries`` the second time an application is built, which is every
test after the first — and the usual fix, a fresh ``CollectorRegistry`` per app,
serves a scrape with nothing attached to it and looks healthy while measuring
nothing.

Deliberately three collectors and no labels beyond ``outcome``. Anything derived
from a request — an account reference, a transaction id, a merchant — would be
unbounded cardinality and, on this data, would also put an identifier into a
metrics label, which §"No secrets, ever" rules out on its own.
"""

from __future__ import annotations

from prometheus_client import Counter, Gauge, Histogram

#: Outcome labels. Fixed and small: a label whose values come from the input is a
#: cardinality bomb, and these three are the only ways a scoring call ends.
OUTCOME_SCORED = "scored"
OUTCOME_INVALID = "invalid"
OUTCOME_UNAVAILABLE = "unavailable"

SCORE_REQUESTS = Counter(
    "sentinelflow_scoring_requests_total",
    "Scoring requests by outcome.",
    ["outcome"],
)

#: Buckets in seconds, chosen for a call the caller budgets a couple of seconds
#: for and expects in milliseconds (ADR-0008 §3). The default buckets start at
#: 5ms and reach 10s, which would put almost every observation in the first
#: bucket and measure nothing useful.
INFERENCE_SECONDS = Histogram(
    "sentinelflow_scoring_inference_duration_seconds",
    "Time spent extracting features and running inference, as measured by this service.",
    buckets=(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5),
)

#: 1 when a model is loaded, 0 when none is. A gauge rather than an ``Info``
#: because the useful alert is "scoring has been serving nothing for ten minutes",
#: which needs a number.
MODEL_LOADED = Gauge(
    "sentinelflow_scoring_model_loaded",
    "1 when a scoring model is loaded and usable, 0 otherwise.",
)

# Initialise the counter's series so a scrape before the first request reports 0
# rather than an absent series. An absent series and a zero look identical in a
# graph and completely different in an alert rule.
for _outcome in (OUTCOME_SCORED, OUTCOME_INVALID, OUTCOME_UNAVAILABLE):
    SCORE_REQUESTS.labels(outcome=_outcome)
