# Shared journey contract fixtures

`loan-origination.journey.json` is the **authoritative DAG config schema
artifact** — the EXACT JSON produced by the Journey DAG Designer's
`ConfigSerializer` (repo `journey-dag-designer`, `contract/`). It is the shared
contract between the designer and this orchestration engine.

**Do not hand-edit this file.** It is a copy of the frontend's emitted output.
A schema change is a co-lock decision made on BOTH sides:

1. Frontend: change the model/serializer, regenerate with
   `dart run tool/emit_contract.dart`, and keep its byte-for-byte round-trip test
   green.
2. Backend: copy the regenerated file here verbatim and keep
   `ContractFixtureTest` (later, the real `JourneyDefinition` loader test) green.

If `ContractFixtureTest` fails, the contract drifted — fix the divergence, do
not bend the parser to a shape the frontend does not emit.
