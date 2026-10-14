# Cloud Stage 4H — Backend Final Stability Validation (2026-09-22)

Goal: separate `WARM_REVISION_STABILITY` from `SCALE_TO_ZERO_COLD_START_BEHAVIOR`
using a genuinely **dedicated** (revision-level, not shared) minimum-instance
guarantee, and decide whether the Backend can finally move to the new
revision. No Backend code, Hikari config, or Cloud SQL config was touched
this stage — only Cloud Run scaling/traffic configuration, exactly as
authorized.

**Result: stopped at the 1% gate.** A revision-level `minScale=1`
(confirmed dedicated via Cloud Run's own `MinInstancesProvisioned`
condition) kept the instance alive and healthy through 4+ idle minutes
with zero traffic, but the same instance was torn down and restarted
repeatedly the moment it started receiving real split traffic. This is a
materially different, more specific finding than Stage 4G's, and it
rules out plain cold-start-from-zero as the mechanism.

## 1. Initial revision

`pfi-backend-dev-00002-jwd` at 100% — confirmed via
`gcloud run services describe --format="value(status.traffic)"` before
any change, matching the state left at the end of Stage 4G.

## 2. Dedicated warm revision (Step 2)

Deployed `pfi-backend-dev-stage4h-warm` via `gcloud run deploy
--image=...@sha256:39ae4cdc...` (the **exact digest** already running as
`stage4f-clean` — no rebuild, no new image), with every other setting
identical to that known-healthy revision (same service account, Cloud SQL
connector env/secrets, GCS env, CPU/RAM/concurrency/timeout/gen2/startup
probe) and the one deliberate addition: `--min-instances=1
--max-instances=2` on the **deploy** command itself, i.e. revision-level,
baked into this specific revision only. Deployed with `--no-traffic
--tag=stage4h-warm`.

## 3. Revision-level min-instance proof (Step 3) — PASS

`pfi-backend-dev-stage4h-warm`'s own annotations, distinct from the
service:

```
autoscaling.knative.dev/minScale: '1'
autoscaling.knative.dev/maxScale: '2'
```

And a condition type not seen in Stage 4G's service-level attempt:

```
type: MinInstancesProvisioned
status: 'True'
message: Min instances provisioned successfully
```

With **zero traffic**, waited ~4 minutes
(`2026-09-22T22:21:08Z`–`22:25:30Z`+), then checked logs for `Shutdown` /
`Starting new instance` events in that window: **none** — only the
original deploy-time start. `Active: True`, `ContainerHealthy: True`
throughout. This is the dedicated-instance guarantee working exactly as
intended, and is a genuinely different (stronger) result than Stage 4G's
service-level `--min`, which never stopped cycling even while nominally
"warm."

## 4. Real 1% traffic, concurrent load (Step 4)

`gcloud run services update-traffic --to-revisions=...00002-jwd=99,...stage4h-warm=1`
— real percentage split, plain service URL, no tags used for the test
traffic itself.

Load generator: no `hey`/`wrk`/`ab` available in this environment: wrote
a small Python `ThreadPoolExecutor`-based client (15 concurrent workers,
1000 requests, alternating `GET /api/system/health` and authenticated
`GET /api/ai/audit`) since none of `hey`, `wrk`, `ab`, or `parallel` were
installed and `xargs -P` alone doesn't track per-request status/latency
as cleanly. This is genuine concurrent load, not sequential.

**Client-side result** (1000 requests, window
`2026-09-22T22:26:43Z`–`22:27:32Z`):

| Status | Count |
|---|---|
| 200 | 989 |
| 503 | 3 |
| 500 | 4 |
| timeout (0) | 4 |

## 5. Routing attribution (Step 5) — authoritative, from Cloud Logging

| Revision | Requests | 200 | 500 | 503 |
|---|---|---|---|---|
| `pfi-backend-dev-00002-jwd` (stable) | 990 | 990 | 0 | 0 |
| `pfi-backend-dev-stage4h-warm` (dedicated, warm) | 11 | **0** | 8 | 3 |

Sample size note: 11 requests landed on the new revision from a 1%
theoretical share of 1000 — in range, and per Step 6 already exceeds the
"no conclusion from <10" floor, though barely; every one of them still
failed.

## 6. 1% gate (Step 6) — FAIL, rolled back

Gate requires ≥10 requests served, 0 unexpected 5xx, 0 timeouts, no
instance churn. Actual: 11 requests served, **11/11 failed** (8×500,
3×503), and heavy instance churn (see §7). **Failed decisively.**

`gcloud run services update-traffic --to-revisions=pfi-backend-dev-00002-jwd=100`
executed immediately; `GET /api/system/health` confirmed `200
{"status":"ok"}` right after. Per Step 6/21, stopped here — Steps 7-20
(10%/50%/100% splits, functional smoke, scale-to-zero cold-start cycles,
Frontend smoke) were **not executed**, since continuing past a decisive
1%-gate failure would only have exposed more traffic to a revision
already proven to fail.

## 7. What the logs show — a different, more specific mechanism than Stage 4G

Full per-request/per-instance-lifecycle logs for `stage4h-warm` during the
test window (abridged pattern, repeats ~3 times across the window):

```
[real request arrives]
... malformed response or connection error ...              503
... HikariDataSource : pfi-postgres-pool - Shutdown initiated...   <- on the "ionShutdownHook" thread: a JVM shutdown signal, not a Hikari-internal failure
... HikariDataSource : pfi-postgres-pool - Shutdown completed.
Starting new instance. Reason: MANUAL_OR_CUSTOMER_MIN_INSTANCE - Instance started because of customer-configured min-instances or manual scaling.
[several concurrent requests immediately fail while no instance exists]
... The request was aborted because there was no available instance ...   500 (x2-4 per cycle)
[~20-25s reboot: Spring, Tomcat, Hikari, all clean]
... Started AiBackendApplication in ~21-25 seconds ...
[one request succeeds -- the STARTUP PROBE itself, server-side, no client timeout]
STARTUP HTTP probe succeeded ... status=200
... HikariDataSource : pfi-postgres-pool - Shutdown initiated...    <- immediately again
Starting new instance. Reason: MANUAL_OR_CUSTOMER_MIN_INSTANCE ...
[cycle repeats]
```

**This rules out plain cold-start-from-zero**: the instance was
demonstrably alive and idle-stable for 4+ minutes before any traffic
arrived (§3). The shutdown hook firing (`ionShutdownHook`, i.e. a JVM
`SIGTERM`-driven hook) means **Cloud Run itself is actively terminating
this instance**, not the application crashing or the instance idling out.
The replacement reason (`MANUAL_OR_CUSTOMER_MIN_INSTANCE`) confirms
Cloud Run immediately starts a *new* instance to satisfy the min-instance
floor -- but it still kills the one that was just asked to serve real
traffic, every time.

The one architectural difference between every test that has **worked**
(the `traffic-tag` header path, local Docker, a single ad-hoc curl) and
every test that has **failed** (a real percentage-based traffic split
through the plain service URL) is precisely that: **real percentage-split
serving specifically**. That strongly suggests Cloud Run's traffic-splitter
path recycles instances on a schedule/policy that a tag-routed or
directly-addressed instance never goes through -- independent of whether
`min-instances` is set at the service or revision level, and independent
of whether the instance was already warm.

## 8. Root cause classification (Step 17 decision matrix)

None of Cases A/B/C fit as literally stated (they assume warm-at-100%
either passes or fails cleanly; this stopped at 1% before reaching 100%).
Closest and most accurate label:

**CASE C — BACKEND_ACTIVE_RUNTIME_NOT_STABLE**, with a refinement over
Stage 4G: the instability is **not** cold-start-from-zero (idle-warm was
proven stable for 4+ minutes) and **not** a shared-vs-dedicated
min-instance configuration issue (dedicated, revision-level min=1 was
used and confirmed via `MinInstancesProvisioned`). It is specific to
**Cloud Run's real percentage-based traffic-split serving path**
recycling the instance under active request load, root cause not yet
identified at the platform level.

## 9. Cost impact and final scaling policy (Steps 18, 22)

- `min=1` (revision-level, on `stage4h-warm` only) was live from
  `2026-09-22T22:20:17Z` (deploy) to `22:29:48Z` (tag removed, revision
  retired) — **~9.5 minutes** of one extra 1 vCPU/512Mi instance. A few
  US cents at most.
- The tag was explicitly removed after rollback
  (`gcloud run services update-traffic --remove-tags=stage4h-warm`) so
  the revision would retire rather than keep costing money while sitting
  at 0% traffic — confirmed via its conditions:
  `Active: False (Retired)`, `MinInstancesProvisioned: False
  (NotProvisioned)`, `TrafficShutDown: True`.
- **Final live scaling policy** (on `pfi-backend-dev-00002-jwd`, the
  serving revision): no `minScale` annotation (default 0), `maxScale=2`
  (service-level, matches the value already baked into this revision at
  its original deploy), concurrency 80, timeout 300s — byte-for-byte the
  same policy the service had before this stage began.
- `pfi-backend-dev-stage4h-warm` was **not deleted** (kept for reference
  per instruction), just detagged and retired.

## 10. Steps not executed (and why)

| Step | Status | Why |
|---|---|---|
| 7-9: 10%/50% splits | Not run | 1% gate failed decisively |
| 10-11: 100% warm result + functional smoke | Not run | same |
| 12-16: scale-to-zero prep, true cold-start cycles ×3, second-warm-request | Not run | requires a stable 100%-warm baseline first, per instruction ordering |
| 19: Hikari/Cloud SQL connection metrics under 100% load | Not run | never reached 100% |
| 20: Frontend smoke | Not run | Backend never reached a stable new-revision state to smoke-test against |

## 11. Final state

- Live traffic: 100% `pfi-backend-dev-00002-jwd`, confirmed healthy.
- `pfi-backend-dev-stage4h-warm`: exists, retired, no tag, 0% traffic,
  not billing.
- `pfi-backend-dev-stage4f-clean` (from Stage 4G): unchanged, still
  retired.
- Service-level scaling: unchanged from pre-Stage-4H baseline
  (`maxScale=2`, no `minScale` override).
- No Backend code, Hikari config, or Cloud SQL config changed.
- No Frontend or AI changes.

## 12. Recommended next investigation (not executed here)

Since the failure is now isolated specifically to Cloud Run's
percentage-split serving path (not cold start, not min-instance
scope), the next diagnostic step should be at the Cloud Run
platform/networking level rather than another application-side
variable: inspect whether `--no-cpu-throttling` interacts unexpectedly
with traffic-split instance recycling, check for any organization
policy or Cloud Run "Direct VPC egress" / networking setting that treats
split-traffic differently, and consider opening a `gcloud feedback` /
Google Cloud support case with this exact log sequence, since the
`ionShutdownHook`-then-`MANUAL_OR_CUSTOMER_MIN_INSTANCE`-replacement
pattern under real traffic-split load (while identical requests via
`traffic-tag` routing succeed without issue) is unusual enough to
warrant platform-side investigation rather than further guess-and-check
against the live service.
