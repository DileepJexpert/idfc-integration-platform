package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest;

import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SoapParseException;
import com.idfcfirstbank.integration.edges.sfdcingress.application.BatchAck;
import com.idfcfirstbank.integration.edges.sfdcingress.application.BatchIngestService;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.AuthTokenPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-slice test of the thin SOAP inbound: auth, all-or-nothing Ack, parse-fault. */
@WebMvcTest(SfdcIngressController.class)
class SfdcIngressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchIngestService batchIngestService;
    @MockitoBean
    private AuthTokenPort authTokenPort;

    private static final String SOAP = "<soapenv:Envelope/>";

    @Test
    void wholeBatchAccepted_returns200AndAckTrue() throws Exception {
        when(authTokenPort.authenticate(anyString())).thenReturn(true);
        when(batchIngestService.ingestBatch(anyString()))
                .thenReturn(new BatchAck(2, 2, true, List.of()));

        mockMvc.perform(post("/api/v1/sfdc/outbound-messages")
                        .header("X-Auth-Token", "dev-token")
                        .contentType(MediaType.TEXT_XML).content(SOAP))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<Ack>true</Ack>")));
    }

    @Test
    void aTransientFailure_returns200AndAckFalse_soSfdcResends() throws Exception {
        when(authTokenPort.authenticate(anyString())).thenReturn(true);
        when(batchIngestService.ingestBatch(anyString()))
                .thenReturn(new BatchAck(2, 1, false, List.of()));

        mockMvc.perform(post("/api/v1/sfdc/outbound-messages")
                        .header("X-Auth-Token", "dev-token")
                        .contentType(MediaType.TEXT_XML).content(SOAP))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<Ack>false</Ack>")));
    }

    @Test
    void unparseableEnvelope_returns500Fault_soAckIsWithheld() throws Exception {
        when(authTokenPort.authenticate(anyString())).thenReturn(true);
        when(batchIngestService.ingestBatch(anyString()))
                .thenThrow(new SoapParseException("no <notifications>"));

        mockMvc.perform(post("/api/v1/sfdc/outbound-messages")
                        .header("X-Auth-Token", "dev-token")
                        .contentType(MediaType.TEXT_XML).content(SOAP))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Fault")));
    }

    @Test
    void badToken_returns401_andNeverIngests() throws Exception {
        when(authTokenPort.authenticate(any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/sfdc/outbound-messages")
                        .contentType(MediaType.TEXT_XML).content(SOAP))
                .andExpect(status().isUnauthorized());
        verify(batchIngestService, never()).ingestBatch(anyString());
    }
}
