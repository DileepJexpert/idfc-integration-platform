# Decision: org scoping — where per-org config keys live in the registry

**Status: PENDING.** Stub created 2026-07-03 so the registry-schema question is
visible BEFORE it gets retrofitted (`docs/legacy-analysis-review.md` §7).

## The question

orgId rides in run context and SELECTS per-org config in the registry — it never
forks a journey. (Confirmed four-for-four across the profiled legacy estate:
org = config, not code.) What remains open is WHERE the per-org config keys live
in the registry schema — per-journey overlay, per-capability-operation map, or a
dedicated org-config document — and how a run's orgId resolves through them.

## Already fixed, not up for re-decision

- orgId never branches a journey definition.
- Unknown orgId fails CLOSED at the boundary (the legacy estate is fail-open —
  `legacy-analysis-review.md` §5.5 — and that behaviour must not be copied).
- Config values only; credentials are `secretRef`s, never values
  (`legacy-analysis-review.md` §6.2).

## Decide by

Before the first migration-target slice (which is itself gated on the §8
pattern census).
