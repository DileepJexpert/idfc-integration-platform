-- FinnOne (mock) — the Oracle stored procedure the lending-origination capability
-- calls over JDBC (NOT REST). Books a loan and returns a loan number (LAN).
--
-- The lending-origination FinnOneStoredProcAdapter invokes:
--   { call SP_FINNONE_SUBMISSION(?, ?) }   IN=applicationRef, OUT=loanNumber
--
-- gvenzl/oracle-xe runs every *.sql in /container-entrypoint-initdb.d once the
-- DB is up, against the demo app user.

CREATE OR REPLACE PROCEDURE SP_FINNONE_SUBMISSION(
    p_application_ref IN  VARCHAR2,
    p_loan_number     OUT VARCHAR2
) AS
BEGIN
    -- Deterministic-ish LAN: prefix + applicationRef + a time suffix.
    p_loan_number := 'LN-' || p_application_ref || '-' ||
                     TO_CHAR(SYSTIMESTAMP, 'YYYYMMDDHH24MISSFF3');
END;
/
