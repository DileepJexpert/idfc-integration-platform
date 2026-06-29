# Demo — Payments as Config Showcase (the third channel, shown not run)

> For Monday (Option B): payments is the THIRD proof that one platform serves all channels — shown as a
> JOURNEY CONFIG in the DAG Designer (config-not-code), not run live. This proves "payments is the same
> pattern: a payment edge + a payments capability + a journey config — no separate repo" without the build
> cost of a live payment flow. Build the live version after Monday.

---

## What you SHOW (not run) for payments
1. **A payments journey as a DAG** in the DAG Designer — e.g.:
   payment-request -> validate-beneficiary -> [branch: rail?] -> execute (IMPS | UPI-mandate | bill-pay) ->
   confirm -> notify.
   Rendered in the SAME DAG Designer, authored the SAME way, versioned + maker-checker + audited the SAME way.
2. **The point you make:** "This payments journey runs on the SAME engine, composing a payments capability
   that routes over rail adapters (IMPS/BillDesk/Montran). The only new pieces are a payment EDGE and the
   payments CAPABILITY — both the same patterns you just saw. No separate repo, no separate platform."

## Why this is honest (not hand-waving)
The evidence already proved the payments shape: every payment service is a thin bridge to an external
execution system (IMPS->IMPS-mgmt-sys, bill-pay->BillDesk, mandate->Montran), and IDFC owns no money-movement
SoR in this layer. So the Payments capability is a ROUTER over rail adapters — structurally identical to the
other capabilities (canonical in/out, vendors as adapters). The DAG + the talk track describe a REAL design,
not a fiction.

## The journey config to author in the DAG Designer (provisional schema)
```json
{
  "key": "payment-execution", "startNodeId": "n_validate",
  "nodes": [
    {"type":"task","id":"n_validate","capabilityKey":"payments","next":["n_route"]},
    {"type":"branch","id":"n_route","arms":[
        {"expression":"rail == 'IMPS'","next":"n_imps"},
        {"expression":"rail == 'UPI_MANDATE'","next":"n_mandate"},
        {"expression":"rail == 'BILL_PAY'","next":"n_bill"}]},
    {"type":"task","id":"n_imps","capabilityKey":"payments","next":["n_confirm"]},
    {"type":"task","id":"n_mandate","capabilityKey":"payments","next":["n_confirm"]},
    {"type":"task","id":"n_bill","capabilityKey":"payments","next":["n_confirm"]},
    {"type":"task","id":"n_confirm","capabilityKey":"payments","next":["n_notify"]},
    {"type":"terminal","id":"n_notify","action":"notify_channel","emit":["PaymentExecuted"]}
  ]
}
```
(Author this in the Designer; it renders as a DAG. The capabilityKey "payments" maps to the payments module —
a stub today; built live after Monday. The rails IMPS/UPI_MANDATE/BILL_PAY are adapter choices inside it.)

## Talk-track line for the payments part of the demo
"Same engine. Same authoring tool. A payment is just another journey over a payments capability that routes to
the right rail — IMPS, UPI mandate, or BillDesk — each a swappable adapter. Onboarding a new rail or a new
payment type is a config change, not a new service. Assisted, digital, payments — one platform, three doors."

## After Monday: the live payments build (deferred, not forgotten)
- payment-edge (edges/payment-edge): receive a payment request (REST/file), normalize, route to the engine.
- payments-capability (capabilities/payments): the router over rail adapters (IMPS-mgmt-sys/BillDesk/Montran),
  canonical in/out, vendors as docker mocks then real. (Build doc: reuse the capability pattern from the Bureau
  doc; it's the same shape.)
- One payment journey live end-to-end, same as the loan flows.
