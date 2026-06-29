# lending-origination capability

On an APPROVED application this capability **books the loan in FinnOne** and
replies with the FinnOne loan account number (LAN).

It implements THE CAPABILITY CONTRACT (`shared:shared-domain`):

- consumes `cap.lending-origination.request.v1`
- produces `cap.lending-origination.response.v1`
- result map: `{ "loanId": "<LAN>", "status": "BOOKED" }`
  (the engine reads `loanId` for the decision — the key is load-bearing)

## The oddity: FinnOne is JDBC, not HTTP

Unlike most vendor integrations here, **FinnOne is reached via an Oracle STORED
PROCEDURE (`SP_FINNONE_SUBMISSION`) over JDBC — NOT a REST/HTTP call.** The real
adapter (`FinnOneStoredProcAdapter`) opens a JDBC `Connection` and a
`CallableStatement` (`{ call SP_FINNONE_SUBMISSION(?, ?) }`): param 1 (IN) is the
`applicationRef`, param 2 (OUT, VARCHAR) returns the LAN. Do **not** model FinnOne
as an HTTP client.

## Mode selection

`idfc.lending-origination.finnone.mode` (env `FINNONE_MODE`, default `mock`):

- `mock` — `MockFinnOneAdapter`, deterministic `LN-<applicationRef>`. No Oracle
  needed; backs unit tests and local/dev runs.
- `real` — `FinnOneStoredProcAdapter` over a JDBC `DataSource` built from
  `spring.datasource.*`.

`DataSourceAutoConfiguration` is excluded on the application class so the app
starts in mock mode without any `spring.datasource.*` configured; in real mode
the config builds the `DataSource` itself.

## Hexagonal layout

- `domain/` — framework-free model (`LoanBooking`) + ports
  (`FinnOneBookingPort`, `CapabilityResponsePort`)
- `application/` — `LendingOriginationService` (the handler)
- `adapter/in/kafka/` — request consumer
- `adapter/out/kafka/` — response publisher
- `adapter/out/finnone/` — `MockFinnOneAdapter`, `FinnOneStoredProcAdapter`
- `config/` — wiring + `FinnOneProperties`
