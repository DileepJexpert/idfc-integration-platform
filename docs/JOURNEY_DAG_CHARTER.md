# Journey DAG Charter — DSL Specification & Engine Contract

| | |
|---|---|
| **Purpose** | The authoritative specification of the Journey DAG: its node grammar (DSL), execution semantics, and the JSON contract. Build the orchestration engine **and** the Flutter Designer against this. |
| **Audience** | build target + architects/reviewers |
| **Status** | **Charter v1.0** — build against this; changes are versioned |
| **Binds** | the **orchestration engine** (executes the JSON) and the **DAG Designer UI** (emits the JSON). One schema, two consumers. |

> **The one rule of this charter:** the **DSL JSON schema (§7)** is the single contract. The engine
> loads it; the Designer produces it. Neither owns logic the other can't see. Design it rich now —
> retrofitting `wait`/`compensation`/`meter` into a running engine later is the expensive mistake
> this charter exists to prevent.

---

## 1. Core concepts (definition vs instance — read this first)

- **Journey definition (the DAG):** the blueprint. **One per product/journey**, versioned. This is what the Designer edits and what this charter specifies. *1 diagram, regardless of volume.*
- **Journey instance (a run):** one execution of a definition for one request (one loan). 1 lakh Diwali loans = 1 lakh **instances** of **one** definition. Instances live in the engine's run-state store; they are **never** rendered as 1 lakh diagrams (see the ops/monitoring view, separate tool).
- **Context:** a typed JSON document carried by each instance. Nodes **read** inputs from it and **write** outputs to it. All conditions and mappings are expressions over `context`.
- **The engine:** stateless workers + a durable run-state store. It **only** traverses the DAG, invokes capabilities, persists state, and enforces policies. **It contains no business logic** — capabilities own decisions (SoC).

---

## 2. The node catalog (the DSL grammar)

Every node shares **common fields**; each `type` adds its own. Each node type is annotated with the
**real service** that proves it's needed.

### Common fields (all nodes)

| Field | Meaning |
|---|---|
| `id` | unique node id within the journey |
| `type` | one of the types below |
| `next` | list of node id(s) to proceed to on success (fan-out if >1) |
| `condition` | optional expression; node runs only if true, else skipped to `next` |
| `onFailure` | failure routing: a node id \| `compensate` \| `dlq` \| `fail` |
| `policies` | optional: `retry`, `timeout`, `circuitBreaker`, `meter` (§3) |

### Node types

| `type` | Purpose | Key fields | Proven by (real service) |
|---|---|---|---|
| **task** | invoke a capability or adapter | `capability`, `operation`, `input`(expr), `output`(ctx key), `compensation`(ref) | every service |
| **branch** | conditional fork | `arms:[{when:expr, next}]`, `default:next` | origination approve/reject |
| **parallel** | fan-out concurrently | `branches:[nodeId...]` (joins at a `join`) | KYC ‖ Bureau ‖ Fraud |
| **join** | wait for predecessors | `joinOn:[nodeId...]`, `policy: allOf\|anyOf\|quorum(n)` | Scoring waits for KYC+Bureau |
| **wait** | park until an async event/callback | `waitFor`(event), `correlation`(expr), `timeout`→`onTimeout` | **EmandateCallback** (Digio/Ingenico/NPCI) |
| **timer** | delay / scheduled continuation | `delay` or `at`(cron) | mandate chase, SLA deadlines |
| **human** | manual task (maker-checker) | `assignTo`(role), `form`, `outcomes:[{value,next}]`, `timeout` | manual underwriting, approval |
| **foreach** | iterate over a collection | `items`(expr), `body`(subgraph), `mode: seq\|parallel(n)` | per-document ack, per-tranche disbursal |
| **subjourney** | call another journey (reuse) | `journey`(key+version), `input`/`output` map | KYC sequence reused by loan + savings |
| **terminal** | end of a path | `emit:[events]`, `action`, `status: completed\|rejected\|failed` | decision push-back |

---

## 3. Per-node policies (cross-cutting, declarative)

Attach to any `task` (or where noted). These are **why the toy DAG was insufficient** — each maps to a real constraint.

| Policy | Fields | Semantics | Proven by |
|---|---|---|---|
| **retry** | `maxAttempts`, `backoff:{type:exponential, base, max}`, `jitter`, `retryOn:[errorClasses]` | retry only on listed transient classes; never on money ops without idempotency key | mssf, brand APIs |
| **timeout** | `duration` | hard cap on the node's call | every external call |
| **circuitBreaker** | `failureThreshold`, `openDuration`, `halfOpenTrial` | open on sustained failure; degrade gracefully | FinnOne, brand vendors |
| **meter** | `pool`(name), `maxConcurrent:N` | **backpressure** — at most N concurrent executions of this node across all instances; excess waits in queue | **FinnOne `SP_FINNONE_SUBMISSION` cap-N** |
| **compensation** | `ref` to a compensating operation | registers an undo for saga (§4) | booking + account saga |

`meter` is the **Diwali scale story in the DSL**: 1 lakh instances hit the Lending-Origination task,
but the `finnone_pool` meter caps FinnOne concurrency at N; the rest wait in Kafka. The burst becomes
queue depth, never amplified onto the stored proc.

---

## 4. Saga & compensation semantics

- A `task` may declare `compensation` (an undo operation on the same capability).
- The **saga boundary is the journey** (or a sub-segment you mark).
- On a failure **after** compensable steps have committed, the engine runs their compensations **in reverse order of completion**, each itself **idempotent and retried**.
- `onFailure: compensate` triggers the saga rollback; the run ends `failed` (or routes to a defined recovery node).
- No distributed 2PC — correctness is **orchestrated compensation**, exactly as the cross-core (FinnOne loan + BaNCS account) flow requires.

Example: book loan (FinnOne) → open account (BaNCS) fails → engine compensates the booking
(reverse/void) → run ends `failed`, decision pushed back as "not completed". No orphaned half-state.

---

## 5. Async / wait-for-callback semantics (the ping-pong)

A `wait` node is how the engine handles vendor callbacks (Digio/Ingenico mandate registration, NPCI verification) without holding a thread:

1. The run reaches `wait`, the engine **persists the parked state** and releases the worker.
2. `correlation` (e.g. `context.mandate.invoiceNo`) is registered as the key to match an inbound event.
3. When the correlated event arrives (Kafka), the engine **resumes** the run at the `wait` node, binds the event into `context`, and proceeds.
4. If `timeout` elapses first → route to `onTimeout` (escalation/chase/fail).

This is what makes EmandateCallback's "register → wait for NPCI verdict → continue" expressible
declaratively instead of as bespoke callback code.

---

## 6. Context & expression language

- Each instance carries a **typed `context`** (JSON). Nodes map `input` from it and write `output` into it.
- **Expression language: a sandboxed, side-effect-free expression syntax** (recommend **CEL** — Common Expression Language — or an equivalent safe subset). Used in `condition`, `branch.arms[].when`, `input`/`output` maps, and config rules.
- Examples:
  - `condition`: `context.product == "personal_loan"`
  - `branch.when`: `context.decision.approved == true && context.bureau.score >= 700`
  - brand rule: `context.brandResponse.Status == "0"` → pass
- **No arbitrary code.** Expressions are parsed and validated against the context schema at publish time. This keeps config authorable in the Designer and safe in production.

---

## 7. The canonical JSON schema (the contract — both sides bind to this)

```jsonc
// journey definition
{
  "journeyKey": "loan-origination",
  "version": 3,
  "context": { "schemaRef": "loan-origination-context@1" },   // typed context
  "pools": { "finnone_pool": { "maxConcurrent": 20 } },        // named meters
  "startNodeId": "customer",
  "nodes": [
    { "id": "customer", "type": "task",
      "capability": "customer-party", "operation": "resolve",
      "input":  "{ panRef: context.request.pan }",
      "output": "context.customer",
      "next": ["kycBureauFraud"] },
    { "id": "kycBureauFraud", "type": "parallel",
      "branches": ["kyc", "bureau", "fraud"] },
    { "id": "kyc",    "type": "task", "capability": "kyc",    "operation": "verify",
      "output": "context.kyc",    "next": ["scoringJoin"],
      "policies": { "retry": { "maxAttempts": 3, "backoff": {"type":"exponential","base":"2s","max":"30s"}, "retryOn":["TRANSIENT"] }, "timeout": "20s" } },
    { "id": "bureau", "type": "task", "capability": "bureau", "operation": "pull",
      "output": "context.bureau", "next": ["scoringJoin"] },
    { "id": "fraud",  "type": "task", "capability": "fraud",  "operation": "screen",
      "output": "context.fraud",  "next": ["decisionJoin"] },
    { "id": "scoringJoin", "type": "join", "joinOn": ["kyc","bureau"], "policy": "allOf", "next": ["scoring"] },
    { "id": "scoring", "type": "task", "capability": "scoring-decisioning", "operation": "decide",
      "output": "context.decision", "next": ["decisionJoin"] },
    { "id": "decisionJoin", "type": "join", "joinOn": ["scoring","fraud"], "policy": "allOf", "next": ["decision"] },
    { "id": "decision", "type": "branch",
      "arms": [{ "when": "context.decision.approved == true", "next": "compliance" }],
      "default": "pushback" },
    { "id": "compliance", "type": "task", "capability": "compliance", "operation": "generateKfs",
      "output": "context.kfs", "next": ["brandValidation"] },
    { "id": "brandValidation", "type": "task",
      "condition": "context.request.isDeviceFinancing == true",
      "capability": "brand-validation-adapter", "operation": "validate",
      "input": "{ brand: context.request.brand, payload: context.request.devicePayload }",
      "output": "context.brand",
      "onFailure": "pushback",
      "next": ["origination"] },
    { "id": "origination", "type": "task",
      "capability": "lending-origination", "operation": "book",
      "input": "{ customer: context.customer, terms: context.decision.terms }",
      "output": "context.loan",
      "policies": { "meter": { "pool": "finnone_pool" }, "timeout": "45s",
                    "circuitBreaker": { "failureThreshold": 0.5, "openDuration": "60s" } },
      "compensation": { "operation": "reverseBooking", "input": "{ loanId: context.loan.id }" },
      "onFailure": "compensate",
      "next": ["accountSaga"] },
    { "id": "accountSaga", "type": "task", "optional": true,
      "condition": "context.request.needsAccount == true",
      "capability": "accounts-casa", "operation": "open",
      "output": "context.account",
      "compensation": { "operation": "closeAccount", "input": "{ accountId: context.account.id }" },
      "onFailure": "compensate",
      "next": ["fanout"] },
    { "id": "fanout", "type": "terminal", "status": "completed",
      "emit": ["LoanSanctioned","LoanDisbursed"], "action": "push_decision_to_channel" },
    { "id": "pushback", "type": "terminal", "status": "rejected",
      "action": "push_decision_to_channel" }
  ]
}
```

Second contract example — **mandate registration with a wait-for-callback** (proves `wait`):

```jsonc
{
  "journeyKey": "emandate-register", "version": 1, "startNodeId": "register",
  "nodes": [
    { "id": "register", "type": "task", "capability": "mandate", "operation": "register",
      "input": "{ vendor: context.request.vendor, mandate: context.request.mandate }",
      "output": "context.registration", "next": ["awaitVerdict"] },
    { "id": "awaitVerdict", "type": "wait",
      "waitFor": "MandateCallback",
      "correlation": "context.registration.invoiceNo",
      "timeout": "24h", "onTimeout": "chase",
      "output": "context.verdict", "next": ["verdictBranch"] },
    { "id": "verdictBranch", "type": "branch",
      "arms": [{ "when": "context.verdict.status == 'SUCCESS'", "next": "done" }],
      "default": "failed" },
    { "id": "chase", "type": "timer", "delay": "2h", "next": ["awaitVerdict"] },
    { "id": "done",   "type": "terminal", "status": "completed", "emit": ["MandateRegistered"] },
    { "id": "failed", "type": "terminal", "status": "failed",    "emit": ["MandateFailed"] }
  ]
}
```

YAML is the human-authoring form; **JSON is the canonical stored/validated form** the engine loads and the Designer emits.

---

## 8. Execution semantics (how the engine runs it)

- **Engine = stateless workers + durable run-state store** (Postgres). No business logic.
- **A run record** holds: `runId`, `journeyKey`, **pinned `version`**, `context`, the **frontier** (completed / active / ready node ids), `status`, timestamps.
- **Step loop:** evaluate **ready** nodes (predecessors satisfied per join `policy`, `condition` true) → invoke each `task` **asynchronously** (publish to the capability's Kafka topic) → on result (consume), bind `output` into context, advance the frontier → persist.
- **Idempotency:** every step is keyed `(runId, nodeId, attempt)`; redelivery/retry never double-executes. Capability calls carry an idempotency key. State transitions are **CAS** on expected prior state.
- **At-least-once everywhere → consumers idempotent.** Crash-safe: the engine rebuilds the frontier from persisted state and resumes (including parked `wait` nodes).
- **Backpressure:** a `meter` pool admits at most N concurrent executions of its node across all runs; excess stays queued (Kafka).
- **Versioning:** in-flight runs keep their **pinned** version to completion; new runs use the **active published** version.

---

## 9. Validation rules (enforced at publish; mirrored in the Designer)

A journey is valid only if:

1. exactly one `startNodeId`; all nodes reachable from it.
2. **acyclic** (graph cycles forbidden; iteration is only via bounded `foreach`).
3. every path reaches a `terminal`.
4. every `join.joinOn` references existing predecessors that actually precede it.
5. every `branch` has a `default` (total coverage).
6. **any node with a `meter`, or any money/booking capability, MUST declare `compensation`** (saga safety).
7. every `wait` declares a `timeout` + `onTimeout`.
8. all `capability`/`operation`/`subjourney` references exist in the registry.
9. all expressions parse against the declared `context` schema.

The Designer runs these **as you draw** (invalid → can't publish); the engine re-validates authoritatively on load.

---

## 10. Implementation tiers (build order)

| Tier | Adds | Runs (real journey) |
|---|---|---|
| **T1 — core** | `task`, `branch`, `parallel`, `join(allOf)`, `terminal`, sequential edges, context+expressions, durable run-state, idempotency, `retry`/`timeout` | linear origination (approve/reject) |
| **T2 — resilience & scale** | `meter` (backpressure), `compensation`/saga, `onFailure` routing, `circuitBreaker`, `join policy: anyOf/quorum` | FinnOne+BaNCS saga; **Diwali backpressure** |
| **T3 — async** | `wait` (event/callback), `timer`/deadline, escalation | **mandate registration** (Digio/Ingenico/NPCI) |
| **T4 — composition** | `subjourney` (reuse), `foreach` (batch), `human` (maker-checker) | KYC reuse; per-doc/tranche; manual underwriting |

The **JSON schema (§7) is defined fully at T1** even though the engine implements types incrementally —
so the contract never changes, only the engine's coverage grows. The Designer is built against the full
schema from the start.

---

## 11. Scope fence (what the engine is NOT)

- Not a capability. It owns **no business state of a domain concept** — only **run state**.
- It makes **no business decision** — `branch`/`condition` evaluate expressions over context that *capabilities* populated.
- It does **not** call cores directly — `task` nodes invoke **capabilities/adapters**.
- If a journey needs logic the DSL can't express → that logic belongs in a **capability/adapter**, not the engine.

---

## 12. Tech stack & contract notes

- **Engine:** Java 21, Spring Boot, **Kafka** (async invoke/result), **Postgres** (run-state, CAS), the DSL stored as **JSON in the journey registry**.
- **Expression language:** CEL (or agreed safe subset) — same evaluator in engine and Designer.
- **Designer (Flutter):** emits exactly the §7 JSON; node-config panels expose the full node + policy model.
- **Registry API:** `GET/POST /journeys/{key}/versions/{v}` stores/loads the §7 JSON; draft→approve→publish (maker-checker); in-flight runs pin their version.

---

## 13. Open inputs to confirm before T2/T3

1. **Context schema per journey** — the typed `context` shape. Start with loan-origination.
2. **`finnone_pool` N** — the safe FinnOne stored-proc concurrency cap.
3. **Callback event contracts** — inbound event shapes + correlation keys for Digio/Ingenico/NPCI (T3 `wait`).
4. **Capability registry** — capability/operation names the `task` nodes reference (also the Designer palette).
5. **Expression evaluator** — confirm CEL vs an alternative; identical in engine + Designer.

---

*Charter v1.0. The §7 JSON schema is the binding contract for the engine and the Designer. Build the
engine in tiers (§10); define the schema fully at T1. Any node type or policy added later extends this
charter under version control — the toy DAG is explicitly superseded.*
