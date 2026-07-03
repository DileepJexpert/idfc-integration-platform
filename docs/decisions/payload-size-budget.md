# Decision: payload size budget — inline limit + rejection behaviour

**Status: PENDING.** Stub created 2026-07-03 so the limit is decided before it
is discovered in production (`docs/legacy-analysis-review.md` §7).

## The question

The platform carries payloads inline (P3.11 content parity); the legacy estate
has real >1MB payloads (the reason its S3 offload exists). Decide:

1. **The maximum inline payload size** the platform accepts — broker message
   limits, Aerospike record limits, and edge validation all key off this one
   number.
2. **The explicit rejection behaviour** at the edge when exceeded — fail
   closed with an enum'd error; never a silent truncation.
3. **Whether an offload seam is needed at all** — only if the pattern census
   (`legacy-analysis-review.md` §8) shows migrated traffic that genuinely
   exceeds the budget. Retention/masking policy rides with that decision
   (the legacy offload's missing policy is finding §5.4).

## Decide by

Before the first migration-target slice; the number must exist before any
file-batch or generic-http traffic lands.
