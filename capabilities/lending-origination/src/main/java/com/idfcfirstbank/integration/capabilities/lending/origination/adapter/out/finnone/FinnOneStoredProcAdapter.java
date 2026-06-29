package com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone;

import com.idfcfirstbank.integration.capabilities.lending.origination.domain.model.LoanBooking;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

/**
 * Real FinnOne adapter. FinnOne is a stored-proc integration, NOT HTTP — do not
 * model it as an HTTP client.
 *
 * <p>Invokes the Oracle stored procedure {@code SP_FINNONE_SUBMISSION(?, ?)} via
 * JDBC {@link CallableStatement}: param 1 (IN) is the {@code applicationRef};
 * param 2 (OUT) returns the FinnOne loan account number (LAN). Active when
 * {@code idfc.lending-origination.finnone.mode=real}.
 */
public class FinnOneStoredProcAdapter implements FinnOneBookingPort {

    private final DataSource dataSource;

    public FinnOneStoredProcAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public LoanBooking book(Map<String, Object> application) {
        String applicationRef = String.valueOf(application.get("applicationRef"));
        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall("{ call SP_FINNONE_SUBMISSION(?, ?) }")) {
            cs.setString(1, applicationRef);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.execute();
            String loanId = cs.getString(2);
            return new LoanBooking(loanId, "BOOKED");
        } catch (SQLException e) {
            throw new IllegalStateException("FinnOne stored-proc booking failed for applicationRef " + applicationRef, e);
        }
    }
}
