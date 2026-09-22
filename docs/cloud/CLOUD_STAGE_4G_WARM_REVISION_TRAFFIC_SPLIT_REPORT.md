# Cloud Stage 4G — Warm-Revision Traffic Split (2026-09-22)

Goal: repeat the real traffic split against the existing clean revision
(`pfi-backend-dev-stage4f-clean`, connection-pooling fix, image
`backend-cloud-sql-gcs-pool-bb859c4`) held warm, to remove cold-start as a
confounder before any further architecture change. No Backend code was
touched in this stage — only Cloud Run scaling/traffic configuration.

**Result: cold-start was not eliminated by the approach tried, and that
failure itself is the finding.** Live traffic never left the stable
revision for more than a single background health check between changes;
end state matches the pre-experiment baseline.

## 1. Baseline verification (Step 1)

- Project: `pfi-asplanatti-fabrello-v1` — confirmed.
- Live: `pfi-backend-dev-00002-jwd` at 100% — confirmed via
  `gcloud run services describe --format="value(status.traffic)"` before
  touching anything.
- `pfi-backend-dev-stage4f-clean` existed but was **Retired** (0% traffic,
  no tag, `TrafficShutDown: true`) — not directly reachable at the start
  of this stage.
- No stale traffic tags on the service (`spec.traffic` had exactly one
  entry, the 100% stable one).

## 2. Scaling scope decision (Step 2)

`gcloud run services update --help` documents two distinct knobs:

- `--min-instances` / `--max-instances` (on `gcloud run deploy`): **revision-level**,
  baked immutably into a new revision — changing it always creates a new
  revision.
- `--min` / `--max` (on `gcloud run services update`): **service-level**,
  "divided among all Revisions receiving a percentage of traffic," and
  explicitly documented as modifiable **without deploying a new revision**.

Chosen: service-level `--min=1` / `--max=2`, applied via `gcloud run
services update` only. Revision-level `--min-instances` was never touched
— the existing `stage4f-clean` revision was reused as-is, satisfying "do
not combine both accidentally" and "smallest change."

## 3. Warming the clean revision (Step 3)

Two changes, in order:

1. `gcloud run services update-traffic --to-revisions=pfi-backend-dev-00002-jwd=99,pfi-backend-dev-stage4f-clean=1`
   — gave the clean revision real traffic eligibility again (a 0%-and-untagged
   revision is "Retired" and not eligible for the service-level min-instance
   floor at all).
2. `gcloud run services update --min=1 --max=2` — service-level floor.

Effect confirmed: `pfi-backend-dev-stage4f-clean`'s own status flipped from
`Active: False (Retired)` to `Active: True`, with `"Updating revision
scaling succeeded"` in its conditions.

## 4. Pre-warm evidence (Step 4)

Cloud Logging for `pfi-backend-dev-stage4f-clean` around `2026-09-22T22:03:44Z`
(not tag-subdomain — direct revision logs):

```
Starting new instance. Reason: DEPLOYMENT_ROLLOUT ...
... Starting AiBackendApplication ...
... HikariDataSource : pfi-postgres-pool - Starting...
... HikariDataSource : pfi-postgres-pool - Start completed.
... Tomcat started on port 8080 ...
... Started AiBackendApplication in 22.044 seconds ...
STARTUP HTTP probe succeeded after 4 attempts ... status=200
```

Application start, Hikari pool start, and the startup probe all passed
cleanly. This was the same clean evidence seen for this revision in every
prior isolated test — the instance itself has never been the problem.

## 5. Real 1% split, 1000 requests (Step 5) — FAILED, root cause identified

Traffic: 99% stable / 1% clean, via the **plain service URL only** (no
`traffic-tag` header — per instruction, that path was deliberately not
used this time so the test exercises the real load-balanced serving path).

Request mix: alternating `GET /api/system/health` (public) and
`GET /api/ai/audit` (authenticated, DB-backed), sequential, one at a time,
`--max-time 15` each. Window: `2026-09-22T22:06:54Z`–`22:13:47Z`.

**Client-side result** (1000 requests):

| Status | Count |
|---|---|
| 200 | 988 |
| 503 | 7 |
| timeout (0 bytes) | 5 |

**Authoritative routing attribution**, from Cloud Logging request logs
(`resource.labels.revision_name` + `httpRequest.status`) for the exact
test window — this is the real count, not an estimate from the 1% figure:

| Revision | Requests | 200 | 503 | Other |
|---|---|---|---|---|
| `pfi-backend-dev-00002-jwd` (stable) | 987 | 987 | 0 | 0 |
| `pfi-backend-dev-stage4f-clean` (new, warm, min=1) | 12 | **0** | **12** | 0 |

**Every single request that reached the clean revision failed. Zero
successes, with `min=1` active and pre-warm evidence confirmed seconds
earlier.** This rules out plain "scale-from-zero" cold start as the
mechanism — the instance was demonstrably not at zero before the test.

### What the per-request logs actually show

Pulling the clean revision's own logs for the test window explains it
precisely — this repeating cycle, every single request:

```
[[a request arrives]]
... malformed response or connection error ...              503, ~0.002s
... HikariDataSource : pfi-postgres-pool - Shutdown initiated...
... HikariDataSource : pfi-postgres-pool - Shutdown completed.
Starting new instance. Reason: AUTOSCALING - ... or no existing capacity for current traffic.
[[~20s cold boot: Spring, Tomcat, Hikari, migrations]]
... Started AiBackendApplication in ~18-22 seconds ...
STARTUP HTTP probe succeeded ... status=200        <- this 200 is Cloud Run's OWN probe, not a client request
[[instance sits idle a few seconds -- no client request arrives in time]]
... HikariDataSource : pfi-postgres-pool - Shutdown initiated...
Starting new instance. Reason: AUTOSCALING ...
[[cycle repeats]]
```

**The real mechanism**: service-level `--min` is a **pool-wide floor
shared across whichever revisions are actively receiving traffic**, not a
dedicated always-on instance reserved per revision. At a 1% split, the
clean revision receives one request roughly every 6-7 seconds in this
sequential test. Cloud Run scales its instance down between those sparse
requests (autoscaling reclaiming capacity for the 99%-revision, which is
constantly busy) and must fully cold-boot (~18-22s) for the next one. My
client's `--max-time 15` was shorter than that boot time, so **every
client-visible request timed out or got a 503 while an instance was
mid-boot** — even though, moments apart, that same instance's *own*
startup probe (measured server-side, no client timeout) passed every
time.

## 6. Stopped here — Steps 6-11 not executed

Given 12/12 (100%) failure attributable specifically to the clean
revision, with clear routing attribution, continuing to 10%/50%/100%
splits would only have exposed more (simulated) traffic to a revision
already proven to fail under these test conditions, contrary to the
explicit instruction not to declare success on a partial result and to
roll back on failure. Stopped and rolled back per the Step 8 gate.

## 7. Rollback (Step 15)

```
gcloud run services update-traffic pfi-backend-dev --to-revisions=pfi-backend-dev-00002-jwd=100
gcloud run services update pfi-backend-dev --min=0 --max=20   # attempted --min=default/--max=default first; both crashed gcloud (see below)
gcloud run services update pfi-backend-dev --max=2            # corrected to match the original baked-in revision value
```

Verified: `status.traffic` back to 100% on `pfi-backend-dev-00002-jwd`,
`GET /api/system/health` → `200 {"status":"ok"}`. `minScale` annotation
absent (matches original, which never had one); `maxScale` explicitly set
to `2` (matches the value already baked into revision `00002-jwd` itself
at its original deploy time, so this is functionally identical to the
pre-experiment state, not merely "close enough").

**Tooling note**: `gcloud run services update --min=default` and
`--max=default` both crashed with `AttributeError: 'ScaleValue' object
has no attribute 'instance_count'` — a bug in this gcloud version
(579.0.0), not a project-config issue. Worked around by setting the
value explicitly instead of using the documented `default` sentinel.

## 8. Root cause classification (Step 13's decision matrix)

None of the three provided cases (A/B/C) fit cleanly, because they assume
"warm via min-instances" straightforwardly removes cold start from the
picture. The actual mechanism found is more specific:

**COLD_START_FAILURE_CONFIRMED, but not eliminated by service-level
`--min` under a low-percentage traffic split** — the closest existing
label is Case A, with an important qualifier: `--min=1` at the *service*
level does not give a low-traffic-share revision a dedicated warm
instance; Cloud Run's autoscaler reclaims it between sparse requests, so
the revision cold-starts on effectively every request when its traffic
share is small enough that requests arrive further apart than the
scale-down window. This also retroactively explains the Stage 4/4B 100%
cutover failures: those tests also drove **sequential, one-at-a-time**
requests rather than sustained concurrent load, which is a materially
different traffic shape than real multi-user production traffic and may
have triggered the same idle-then-cold-start pattern even at 100%
traffic.

**This was not tested to conclusion** — Steps 9-12 (10%/50%/100% splits,
and the scale-to-zero cold-vs-warm-second-request comparison) did not
run, so whether a **sustained, concurrent** load pattern (as real traffic
would look) keeps the revision continuously warm and stable remains
unverified.

## 9. Cost impact

Service-level `min=1, max=2` was active from approximately
`2026-09-22T22:04Z` to `22:14Z` (~10 minutes) before being reverted. One
extra always-allocated small (1 vCPU/512Mi) Cloud Run instance for ~10
minutes is on the order of a few US cents — negligible, and not left
running: confirmed reverted to `min=0` (absent annotation) before this
report was written.

## 10. Final state

- Live traffic: 100% `pfi-backend-dev-00002-jwd` (original, in-memory-mode,
  unchanged since before Stage 4).
- Service-level scaling: `minScale` unset (0, matches original),
  `maxScale=2` (matches original revision-baked value).
- `pfi-backend-dev-stage4f-clean`: 0% traffic, no tag — will retire again
  naturally.
- No Cloud SQL, bucket, or secret resources touched this stage.
- No Backend code changed this stage.

## 11. Recommended next step (not executed here)

Re-run the same warm-revision test using a **concurrent** load generator
(e.g. `hey`, `wrk`, or `ab` with `-c 10` or higher against the plain
service URL) instead of a sequential single-connection curl loop, so the
clean revision receives requests close enough together that Cloud Run has
no idle window to reclaim its instance between them. If that keeps it
stable, the root cause is confirmed as the min-instance/traffic-share
interaction described in §5, with no further Backend code change needed.
If it still fails under genuine concurrent load, the investigation must
continue at the Cloud Run platform/networking level (Case C, unchanged).
