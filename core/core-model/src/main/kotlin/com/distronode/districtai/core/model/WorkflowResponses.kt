package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * The most recent execution of one workflow, as the LIST route rolls it up.
 *
 * ⛔ NOT A [WorkflowRun]. The list route selects three columns off `WorkflowRun`
 * (`workflowId`, `status`, `startedAt`) and publishes only the last two; the runs route returns
 * eight fields including the per-action outcomes. A client that modelled this as a run would be
 * asserting the presence of `actionResults` on a payload that has never carried it — and with
 * `ignoreUnknownKeys = false` the reverse mistake (decoding a real run into this) fails loudly,
 * which is the direction worth having.
 *
 * ⚠️ `status` IS FREE TEXT ON THE WIRE. `WorkflowRun.status` is a plain String column server-side
 * — there is no Prisma enum — so the four values the engine writes today
 * (`success`/`partial`/`failed`/`skipped`) are a convention rather than a guarantee. Kept as a
 * String for the same reason `WorkspaceRole` is parsed rather than decoded: an enum here would
 * throw on an unmodelled value and take out the whole list it arrived in.
 */
@Serializable
data class WorkflowLatestRun(
    val status: String = "",
    /** ISO-8601 string — `NextResponse.json` serialises a Date through `JSON.stringify`. */
    val startedAt: String = "",
)

/**
 * One row of `GET /api/district/workflows`.
 *
 * ⛔ `latestRun` IS NULL FOR A WORKFLOW THAT HAS NEVER RUN, AND THE ROUTE SENDS AN EXPLICIT NULL
 * RATHER THAN OMITTING THE KEY — its own comment says so, because a missing key would be
 * indistinguishable from a stale client. Both branches are in the fixtures for that reason: a DTO
 * that regressed to non-null would throw on the most ordinary row there is, a workflow somebody
 * created five minutes ago.
 *
 * ⚠️ `trigger` IS FREE TEXT AND THE CLIENT MUST NOT EXHAUST OVER IT. The column is a bare String
 * and the server validates it against `WORKFLOW_TRIGGERS` on write — ten values today — but that
 * list grows, and an already-installed build has to keep rendering a workflow whose trigger it has
 * never heard of. See `triggerLabel` in the workflows UI, which falls back to the raw string.
 */
@Serializable
data class WorkflowListItem(
    val id: String = "",
    val name: String = "",
    val active: Boolean = false,
    val trigger: String = "",
    val createdAt: String = "",
    val latestRun: WorkflowLatestRun? = null,
)

/**
 * `GET /api/district/workflows`
 *
 * ⚠️ ADMITS `viewer`, unlike the POST/PATCH/DELETE on the same path. The route header states the
 * reason: a monitor is a read, and nothing in this payload is a credential or a phone number.
 * So the SCREEN is reachable by every role; only the toggle is gated.
 *
 * ⚠️ Ordered `createdAt desc`, newest first.
 */
@Serializable
data class WorkflowListResponse(
    val success: Boolean = false,
    val workflows: List<WorkflowListItem> = emptyList(),
)

/**
 * What one action inside a run did.
 *
 * ⛔ `reason` IS PRESENT ONLY ON SOME OUTCOMES, AND IT IS THE MOST USEFUL FIELD ON THE ROW. The
 * engine attaches it when an action was SKIPPED (no contact, no phone number, missing metadata) —
 * i.e. exactly the case where "skipped" alone tells an operator nothing they can act on. Nullable
 * because the ordinary `ok` row does not carry it.
 *
 * ⚠️ `outcome` IS FREE TEXT for the same reason `status` is: the column is `Json` server-side and
 * this object is written into it by the engine, so nothing type-checks the vocabulary across the
 * boundary. `ok`/`skipped`/`failed` are what it writes today.
 */
@Serializable
data class WorkflowActionResult(
    val type: String = "",
    val outcome: String = "",
    val reason: String? = null,
)

/**
 * One execution of one workflow.
 *
 * ⛔ `error` IS THE WHOLE-RUN FAILURE AND IS NOT THE SAME AS AN ACTION'S `reason`. A run can be
 * `failed` with every action reporting its own outcome, and it can be `failed` with an empty
 * `actionResults` because the engine threw before any action ran. A screen that showed only the
 * per-action rows would render the second case as a failure with no explanation at all.
 *
 * ⚠️ `finishedAt` IS NULL FOR A RUN THAT DID NOT FINISH. The column is nullable server-side and
 * the route maps it to an explicit null.
 *
 * ⚠️ `actionResults` DEFAULTS TO EMPTY AND THE ROUTE GUARANTEES AN ARRAY. The column is `Json`, so
 * the route wraps the read in `Array.isArray(...) ? ... : []` — a non-array in the database becomes
 * an empty list rather than a decode failure on the phone.
 */
@Serializable
data class WorkflowRun(
    val id: String = "",
    val workflowId: String = "",
    val trigger: String = "",
    val status: String = "",
    val startedAt: String = "",
    val finishedAt: String? = null,
    val actionResults: List<WorkflowActionResult> = emptyList(),
    val error: String? = null,
)

/**
 * `GET /api/district/workflows/runs`
 *
 * ⚠️ A REAL `total` AND A REAL `hasMore`, unlike the call log — so end-of-list is KNOWN rather than
 * inferred from a short page. `limit` and `offset` are ECHOED as the server applied them: `limit`
 * is clamped to 1..50 (default 10) and a non-numeric value is replaced rather than clamped, so the
 * echo is the only way a client learns what it actually got.
 *
 * ⚠️ Ordered `startedAt desc`, newest first.
 */
@Serializable
data class WorkflowRunsResponse(
    val success: Boolean = false,
    val runs: List<WorkflowRun> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val hasMore: Boolean = false,
)

/**
 * `PATCH /api/district/workflows`
 *
 * ⛔ A BARE `{success:true}` — THE UPDATED ROW IS NOT ECHOED. So a caller that needs fresh state
 * must re-read, and a caller that toggled optimistically has nothing to reconcile against. That is
 * why the toggle in this client reverts on FAILURE rather than adopting a response on success:
 * there is nothing in the response to adopt.
 */
@Serializable
data class WorkflowToggleResponse(
    val success: Boolean = false,
)

/**
 * The three always-on-SDR fields.
 *
 * ⛔ ALL THREE ARE NORMALISED SERVER-SIDE AND THE NULLS ARE LOAD-BEARING. `campaignSettings` is a
 * nullable `Json` column whose keys are all optional, so the route collapses "absent" and "off"
 * into one rendering: `infiniteSdrEnabled` is `!!settings.infiniteSdrEnabled`, `sdrBatchSize` is
 * null rather than 0 (the settings PATCH floors it to at least 1, so 0 is a value that cannot be
 * stored), and an empty `sdrCampaignGoal` collapses to null (the PATCH writes `goal || ""`, so a
 * workspace that opened the tab and typed nothing stores `""` while one that never opened it has
 * no key — the same state to a reader).
 *
 * ⚠️ `sdrBatchSize` IS AN `Int?` AND THE COLUMN IS UNTYPED JSON. The only writer floors it
 * (`Math.floor(Number(x) || 1)`), so every stored value is a whole number; a fractional one would
 * be a decode failure rather than a rounded display, which is the right way round for a figure
 * that decides how many people get called.
 */
@Serializable
data class CampaignStatus(
    val infiniteSdrEnabled: Boolean = false,
    val sdrBatchSize: Int? = null,
    val sdrCampaignGoal: String? = null,
)

/**
 * `GET` **and** `PATCH /api/district/workspace/campaign-status`
 *
 * ⛔ ONE TYPE FOR BOTH VERBS, BECAUSE THE ROUTE DELIBERATELY ANSWERS THE READ'S SHAPE FROM THE
 * WRITE. The PATCH derives its reply from the object it merged rather than re-reading it, so the
 * client can render the result of a pause without a second round trip — and a second DTO here
 * would let the read and the write drift into disagreeing about what `null` means for a batch size.
 *
 * ⛔ AND THIS IS NOT `campaign-settings`. That route rebuilds all three SDR fields from its request
 * body, so a partial body sent to pause a campaign WIPES the goal text and resets the batch size to
 * 1, with a 200. `campaign-status` spreads the stored Json and assigns ONE key. This paragraph used
 * to say that no pause control could exist anywhere in this client; the narrow route is what
 * changed, not the danger of the wide one.
 *
 * ⚠️ THE TWO VERBS DIFFER ON ROLE: the GET admits `viewer`, the PATCH does not. Watching a campaign
 * and pausing one are different powers, and that split is the reason this route can admit the
 * lowest-trust role at all.
 *
 * ⚠️ `campaign` IS NULLABLE ONLY TO SURVIVE A `{}` BODY. Both verbs always send it on a 200, so a
 * null here is contract drift rather than a state — `WorkflowsRepository` rejects it as such
 * instead of rendering an unconfigured campaign.
 */
@Serializable
data class CampaignStatusResponse(
    val success: Boolean = false,
    val campaign: CampaignStatus? = null,
)
