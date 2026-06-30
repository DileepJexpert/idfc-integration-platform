-- FinnOne (mock) — the Oracle stored procedure the lending-origination capability
-- calls over JDBC (NOT REST). Books a loan and returns a loan number (LAN).
--
-- The lending-origination FinnOneStoredProcAdapter invokes:
--   { call SP_FINNONE_SUBMISSION(?, ?) }   IN=applicationRef, OUT=loanNumber
--
-- gvenzl/oracle-xe runs every *.sql in /container-entrypoint-initdb.d once, during
-- DB initialization, AS SYS CONNECTED TO THE CDB ROOT (XE). Without the explicit
-- container switch below the procedure lands under SYS in the root container, where
-- the application (which connects as finnone@XEPDB1) cannot see it
-- (PLS-00201: identifier 'SP_FINNONE_SUBMISSION' must be declared). So we hop into
-- the XEPDB1 pluggable DB and create it in the finnone application schema.
ALTER SESSION SET CONTAINER = XEPDB1;

CREATE OR REPLACE PROCEDURE finnone.SP_FINNONE_SUBMISSION(
    p_application_ref IN  VARCHAR2,
    p_loan_number     OUT VARCHAR2
) AS
BEGIN
    -- Deterministic-ish LAN: prefix + applicationRef + a time suffix.
    p_loan_number := 'LN-' || p_application_ref || '-' ||
                     TO_CHAR(SYSTIMESTAMP, 'YYYYMMDDHH24MISSFF3');
END;
/
