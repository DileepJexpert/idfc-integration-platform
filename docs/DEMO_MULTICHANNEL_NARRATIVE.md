# Monday Demo — Multi-Channel Narrative (the "one platform, no separate repos" proof)

| | |
|---|---|
| **The thesis to prove** | One platform serves ASSISTED + DIGITAL + PAYMENTS. No separate repo/stack per channel. |
| **How** | Three doors (edges) feed ONE engine + ONE capability core. Swap the edge; the middle is identical. |
| **Monday scope (Option B)** | Assisted + Digital LIVE through the same core; Payments shown as config (DAG). |

---

## The single picture that proves everything
```
  ASSISTED            DIGITAL              PAYMENTS
  (Salesforce)        (CRED/Flipkart)      (payment request)
      │                   │                    │
      ▼                   ▼                    ▼
 [sfdc-ingress-edge] [digital-partner-edge] [payment-edge]      <- ONLY the edge differs per channel
      │                   │                    │
      └───────────────────┴────────────────────┘
                          │  same canonical envelope, same topic
                          ▼
              [origination-journey ENGINE]                      <- ONE engine, config-driven
                          │  composes (journey = DAG config)
                          ▼
   customer-party → bureau → scoring → [branch] → lending-origination     <- ONE capability core, shared
                          │
                          ▼
                 decision back to the channel
```
LIVE on Monday: the assisted and digital paths (solid). Shown as config: the payments path (its journey DAG in
the Designer). The capability core and engine are byte-identical across channels.

## The demo sequence (what you run, in order)
1. **Assisted loan (SFDC edge).** curl/SFDC-mock -> sfdc-ingress-edge -> engine -> customer/bureau/scoring/
   lending -> decision back. "This is the assisted channel."
2. **Digital loan (digital edge).** A partner (CRED) REST call -> digital-partner-edge -> **the SAME engine ->
   the SAME capabilities** -> decision back. "Different channel, different door — and watch: the same engine,
   the same capabilities, the same journey. Nothing in the core changed. I only added a thin edge."
3. **Show the envelopes side by side.** The canonical envelope from the SFDC edge and from the digital edge are
   the SAME shape (the thesis test asserts this). "The core literally cannot tell which channel sent it."
4. **Payments (config).** Open the DAG Designer, show the payment-execution journey as a DAG. "Payments is the
   same pattern — a payment edge + a payments capability routing over rails — authored the same way, run by the
   same engine. A new rail or payment type is a config change."
5. **Scale (assisted edge).** 10x burst -> queue depth, FinnOne cap held. "And it absorbs bursts without
   toppling the core."
6. **Config-not-code (Designer).** Show a journey as a DAG; change a step; maker-checker; publish. "A new
   product, partner, or channel is config — not a new microservice."

## The headline lines (say these)
- "Today, assisted lives in one set of services, digital in another, payments in a third — each rebuilding the
  same KYC, bureau, scoring, lending. That's the ~70-service sprawl."
- "Here, all three channels are just DOORS onto ONE shared capability core, sequenced by ONE config-driven
  engine. The door changes; the core never does."
- "So there is NO separate repo for digital, no separate platform for payments. There's one platform, and a
  thin edge per channel. ~70 services become ~20, and a new channel/partner/product is a config change."

## The cross-questions this demo pre-empts
- "Does this only work for assisted/SFDC?" -> No — here's a digital partner loan through the SAME core, live.
- "Wouldn't digital need its own stack?" -> It needs ONE thin edge. The engine + capabilities are untouched —
  I didn't change a line of them to make digital work. (The envelope-identical test proves it.)
- "What about payments — surely that's separate?" -> Same pattern: a payment edge + a payments capability over
  rails. Here's its journey in the same Designer. Built next, same way.
- "How do you add a new partner/channel?" -> Config. A partner is a config row; a journey is a DAG; a channel
  is a thin edge. None of it is a new core service.

## Build implication (Option B, fits the 8 days)
The ONLY net-new build for the multi-channel proof is **edges/digital-partner-edge** (one thin edge, ~1 day —
prompt: DEMO_DIGITAL_EDGE_PROMPT.md). Payments is config-only (DEMO_PAYMENTS_CONFIG_SHOWCASE.md). The engine
and capabilities are the ones already on the 8-day plan — they are reused unchanged, which is the entire point.

## Updated 8-day fit
- Insert the digital edge after the capabilities exist and the assisted flow runs (around day 5-6), because it
  reuses the same engine/capabilities/envelope — it's cheap once the core works.
- Day 6 also: author the payments journey DAG in the Designer (config showcase).
- The digital edge's "envelope identical to SFDC" test is the proof artifact — keep it; it's your evidence.
