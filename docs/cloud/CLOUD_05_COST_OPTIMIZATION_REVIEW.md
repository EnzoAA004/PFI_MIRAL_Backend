# CLOUD-05 — Cost Optimization Review (db-f1-micro re-audit)

Triggered by an explicit user challenge to the original CLOUD-05 conclusion that `db-f1-micro` was
unavailable for new instances. **The challenge was correct — the original conclusion was wrong.**
This document is the full re-audit: what was checked, what evidence was found, and the corrected
design. `CLOUD_05_CLOUD_SQL_DESIGN.md` and `CLOUD_05_COST_CHECK.md` have been updated in place to
match; this document is the detailed record of the investigation itself.

## 1. Is `db-f1-micro` available for a new PostgreSQL 15 instance in `us-central1`? **YES.**

Confirmed by two independent, read-only, non-mutating mechanisms — no instance was created, no API
was enabled.

## 2. Evidence used

**(a) Cloud SQL Admin API `tiers.list`** — a live, authoritative catalog of currently-orderable
tiers:

```
GET https://sqladmin.googleapis.com/sql/v1beta4/projects/<project>/tiers
```

Returned `db-f1-micro` with `"edition": "ENTERPRISE"` and `us-central1` present in its `region` list.
This call succeeded even though `sqladmin.googleapis.com` is confirmed **disabled** on the project
(re-verified via `gcloud services list --enabled --filter="config.name:sqladmin.googleapis.com"` →
empty result) — `tiers.list` is a static reference endpoint, not a project-scoped resource query, so
it does not require the API to be enabled and creates nothing.

**(b) Cloud Billing Catalog API, fully paged** — the original CLOUD-05 pass fetched only 3 pages
(15,000 of the Cloud SQL service's actual ~40,500 SKUs; every one of those 3 pages carried a
`nextPageToken`, meaning pagination was never exhausted) and searched for the literal substring
`"f1-micro"`. That substring only appears in **Extended-support**-labeled SKU descriptions (tied to
EOL major versions 9.6–13) — it does **not** appear in the current, regular-support pricing for the
same tier, which Google labels differently: `"Micro instance"` (not `"f1-micro"`) and
`"Small instance"` (not `"g1-small"`). Re-fetching all ~40,500 SKUs and searching for `"Micro
instance"` found:

```
C2D4-F7DF-B8D0 | Cloud SQL for PostgreSQL: Zonal - Micro instance in Americas | $0.0105/hour
```

— a live, current, `us-central1`-covered, non-extended-support SKU. This is the real root cause of
the original error: an incomplete text search combined with unexhausted pagination, not a genuine
platform limitation.

## 3. Edition

**`ENTERPRISE`** — confirmed by `tiers.list`'s `edition` field for `db-f1-micro`. `ENTERPRISE_PLUS`
(the C4/N4 dedicated-core edition family) does not support shared-core tiers; `ENTERPRISE` is
required and is now set explicitly in the Terraform module (`settings.edition = "ENTERPRISE"`, new
`edition` variable, default `"ENTERPRISE"`).

## 4. Exact Terraform tier

`tier = "db-f1-micro"` — now the `cloud-sql` module's default (`infra/terraform/modules/cloud-sql/
variables.tf`), inherited by `environments/dev` without an override. The module still accepts any
dedicated custom tier (e.g. `db-custom-1-3840`) as an explicit override for a future DEMO/prod-like
environment — no code path forces custom vCPU+memory sizing.

## 5. Plan flags-off (Plan A)

Re-run after the tier/edition change, against real live state:

```
No changes. Your infrastructure matches the configuration.
```

**0 to add, 0 to change, 0 to destroy** — confirms the tier/edition change is inert while
`enable_cloud_sql` stays `false`.

## 6. Plan flags-on (Plan B, `enable_cloud_sql=true`, `enable_secrets=true`, `db-f1-micro`)

Full raw output: `CLOUD_05_TERRAFORM_PLAN_OUTPUT_F1MICRO.txt`. The provider **accepted the tier
without error** — no schema-level or API-level rejection at plan time. Relevant diff lines:

```
+ edition                          = "ENTERPRISE"
+ tier                             = "db-f1-micro"
```

## 7. add/change/destroy

**Plan: 7 to add, 1 to change, 0 to destroy** — identical resource topology to the `db-custom-1-3840`
plan reviewed earlier (same 7 creates: Cloud SQL instance, `pfi` database, 2 secret containers,
`cloudsql.client` IAM binding, 2 secret-accessor IAM bindings; same 1 Backend Cloud Run revision
update). Only the instance's internal `tier`/`edition` settings differ — no additional or missing
resource compared to the previous plan.

## 8. 1-hour cost

**~$0.0105** (compute only; storage is billed monthly, not meaningfully attributable to a single
hour).

## 9. 24-hour cost

**~$0.31** — `$0.0105 × 24 = $0.252` compute + `$1.70 ÷ 30 ≈ $0.057` storage (prorated).

## 10. 7-day cost

**~$2.16** — `$0.0105 × 168h = $1.764` compute + `$1.70 ÷ 30 × 7 ≈ $0.397` storage (prorated).

## 11. 30-day cost

**~$9.37/month** — `$0.0105 × 730h = $7.665` compute + `10 GB × $0.17/GB-mo = $1.70` storage. An 82%
reduction versus the previously reviewed `db-custom-1-3840` figure (~$51.01/month).

## 12. Stopped-instance cost behavior — important, non-obvious finding

Stopping (`activation-policy=NEVER`) halts **compute** billing, but two other charges keep running:

- **Storage**: `10 GB × $0.17/GB-mo = $1.70/month`, always, running or stopped.
- **Reserved IP address, if idle**: Cloud SQL's public pricing includes a distinct
  `"IP address reservation"` charge (SKU `3151-8F6A-75C1`, `$0.01/hour`, confirmed covering
  `us-central1`) that applies specifically when a reserved external IP is **not** attached to a
  running instance — i.e., exactly the "stopped" state this design would use to save money. If the
  static IP is left allocated while stopped, that's `$0.01 × 730h ≈ $7.30/month` on its own.

**Net effect: for `db-f1-micro`, stopping the instance saves very little.** Running 24/7 costs
~$9.37/month; stopped with the IP still reserved costs approximately `$1.70 + $7.30 = $9.00/month` —
a savings of only ~$0.37/month, because the idle-IP charge nearly offsets the (already tiny) compute
savings. This is the opposite of the previous `db-custom-1-3840` case, where stopping saved ~$49 of a
~$51 monthly bill (storage-only cost, no separate IP-reservation line item was material at that
scale of comparison). **Conclusion: for this tier, the stop/start lever is not a meaningful cost
optimization** — it's still available operationally (e.g. to reduce load or for a hard pause), but
should not be presented to the user as a way to meaningfully cut the ~$9.37/month figure.

## 13. Storage-only monthly cost

**~$1.70/month** (10 GB × $0.17/GB-month) — unaffected by tier choice, applies whether the instance
runs, is stopped with the IP released, or (per item 12) is stopped with the IP still reserved (in
which case add the idle-IP charge on top).

## 14. Start/stop strategy (commands documented, not executed)

```bash
# STOP (halts compute billing; storage and, if the IP stays allocated, the
# idle-IP charge per item 12 continue)
gcloud sql instances patch pfi-postgres-dev --activation-policy=NEVER

# START
gcloud sql instances patch pfi-postgres-dev --activation-policy=ALWAYS
```

Given item 12's finding, this lever is kept documented for operational flexibility (e.g. pausing
during a long absence, or before/after a specific validation session) but is **not** the primary cost
control for this tier — the tier choice itself (`db-f1-micro` vs. `db-custom-1-3840`) is.
`activation_policy` is an instance runtime setting, not a Terraform-managed attribute in this
module's `google_sql_database_instance` resource — toggling it via `gcloud` does not create Terraform
drift (`terraform plan` would not attempt to reconcile it, since the module never sets
`settings.activation_policy` explicitly one way or the other, leaving it outside Terraform's declared
state).

## 15. Limitations of shared-core (`db-f1-micro`)

- **~614 MiB RAM**, fixed — not independently configurable. Adequate for a thesis DEV/smoke-test
  database; would need re-evaluation under real concurrent load or a larger working set.
- Disk (10 GB PD_SSD) and disk type are independent of the tier — unaffected by this change.
- CPU is shared with other tenants (the defining trait of "shared-core") — acceptable for DEV, not
  for latency-sensitive workloads.

## 16. SLA limitations

Per Google's own Cloud SQL documentation, shared-core machine types (`db-f1-micro`, `db-g1-small`)
are **not covered by the Cloud SQL SLA** and are explicitly described as intended for
low-cost test/development use, not production. This is an accepted tradeoff for DEV; **DEMO should
weigh this explicitly** — an SLA-free database is a real risk if a live demo depends on it staying up
uninterrupted, though the ~$41/month difference to a dedicated tier is a separate, small cost by this
project's standards either way.

## 17. Migration compatibility

Unaffected. The CLOUD-05 migration validation (17 migrations against a clean local Postgres,
idempotency + restart-survival confirmed) tested SQL/schema correctness and the JDBC driver's TCP
connectivity path — neither depends on instance RAM/CPU sizing. `db-f1-micro`'s smaller resource
envelope affects headroom under load, not correctness.

## 18. Secrets/networking — unchanged

No change to the Secret Manager design (2 secret containers only, no `secret_version`, values
out-of-band — see `CLOUD_05_SECRET_MANAGER.md`) or the networking design (Cloud SQL Auth Proxy
sidecar, public IP with empty `authorized_networks`, no `0.0.0.0/0` — see `CLOUD_05_CLOUD_SQL_DESIGN.md`
"Networking"). The tier/edition change only affects the `google_sql_database_instance.settings`
block's `tier`/`edition` fields.

## 19. Git push result for `d4d31d4`

**Succeeded.** `git push -u origin cloud/cloud-05-managed-persistence` pushed commit `d4d31d4` to a
new remote branch `cloud/cloud-05-managed-persistence` on `EnzoAA004/PFI_MVPTest_Enzo_Backend`. No
merge, no pull request opened, `main` untouched — GitHub's own "Create a pull request" hint in the
push output was not acted on. This re-audit's own changes (tier/edition correction + this document)
are **not yet committed** — pending your review of this report.

## 20. Blockers

None technical — the corrected design plans cleanly (`terraform validate` passes, Plan A shows 0
diff, Plan B shows the same 7-add/1-change/0-destroy topology as before, just at the corrected tier).
Process blockers unchanged: **cost approval and apply authorization are still required** before any
`terraform apply`. `sqladmin.googleapis.com` and `secretmanager.googleapis.com` remain disabled; no
Cloud SQL instance, no secret, no IAM binding has been created.

---

**NO terraform apply. NO Cloud SQL create. NO API enable.** Awaiting your decision on the corrected
~$9.37/month `db-f1-micro` proposal (versus the previously reviewed ~$51.01/month `db-custom-1-3840`
proposal, still available as a fallback if `db-f1-micro`'s ~614 MiB RAM or lack of SLA proves
insufficient later).
