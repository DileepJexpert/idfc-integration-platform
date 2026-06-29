# kyc capability

Verifies the applicant's KYC against NSDL (the vendor). Reads applicant identity
(`pan`, `name`, `dob`) from the request payload and returns
`{ "kycStatus": "VERIFIED", "kycRefId": "<id>" }`.

## Contract (shared:shared-domain)

- consumes `cap.kyc.request.v1` (`CapabilityRequest`)
- produces `cap.kyc.response.v1` (`CapabilityResponse`)
- topics derived via `CapabilityTopics.request("kyc")` / `.response("kyc")`
- JSON String serde (matching the engine)

Hexagonal: the domain is framework-free; NSDL sits behind the `NsdlPort` OUT
port with a mock (local/test) and a real (HTTP) adapter.

## Mock / real toggle

Selected by config `idfc.kyc.nsdl.mode` (default `mock`):

- `mock` — `MockNsdlAdapter`, deterministic (`KYC-<pan>`), no vendor needed.
- `real` — `NsdlHttpAdapter`, HTTP POST `/nsdl/verify` to `idfc.kyc.nsdl.url`.

```yaml
idfc:
  kyc:
    nsdl:
      mode: ${NSDL_MODE:mock}
      url: ${NSDL_URL:http://localhost:9104}
```
