package in.idfc.integration.edges.sfdcingress.adapter.in.rest;

import in.idfc.integration.edges.sfdcingress.application.EdgeDisposition;
import in.idfc.integration.edges.sfdcingress.application.EdgeResult;
import in.idfc.integration.edges.sfdcingress.application.SfdcIngressService;
import in.idfc.integration.edges.sfdcingress.domain.port.AuthTokenPort;
import in.idfc.integration.edges.sfdcingress.domain.port.MessagePublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-slice test of the thin inbound: auth, ACK/no-ACK mapping (C2), schema-invalid. */
@WebMvcTest(SfdcIngressController.class)
class SfdcIngressControllerTest {

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SfdcIngressService ingressService;
    @MockitoBean
    private AuthTokenPort authTokenPort;
    @MockitoBean
    private MessagePublisherPort publisher;

    private static final String VALID_BODY = """
            {"notificationId":"n1","correlationId":"c1","sfdcRecordId":"r1",
             "applicationRef":"a1","orgId":"ORG1","type":"PERSONAL_LOAN","payload":{"x":1}}""";

    @Test
    void processed_returns200() throws Exception {
        when(authTokenPort.authenticate(anyString())).thenReturn(true);
        when(ingressService.ingest(any())).thenReturn(
                new EdgeResult(EdgeDisposition.ACK_PROCESSED, "n1", "ok"));

        mockMvc.perform(post("/api/v1/sfdc/notifications")
                        .header("X-Auth-Token", "dev-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("ACK_PROCESSED"));
    }

    @Test
    void transient_returns503_soSfdcRedelivers() throws Exception {
        when(authTokenPort.authenticate(anyString())).thenReturn(true);
        when(ingressService.ingest(any())).thenReturn(
                new EdgeResult(EdgeDisposition.RETRY_TRANSIENT, "n1", "broker down"));

        mockMvc.perform(post("/api/v1/sfdc/notifications")
                        .header("X-Auth-Token", "dev-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void badToken_returns401() throws Exception {
        when(authTokenPort.authenticate(any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/sfdc/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verify(ingressService, never()).ingest(any());
    }

    @Test
    void schemaInvalid_isAckedAndDlqd() throws Exception {
        when(authTokenPort.authenticate(anyString())).thenReturn(true);
        String missingNotificationId = """
                {"orgId":"ORG1","type":"PERSONAL_LOAN","payload":{"x":1}}""";

        mockMvc.perform(post("/api/v1/sfdc/notifications")
                        .header("X-Auth-Token", "dev-token")
                        .contentType(MediaType.APPLICATION_JSON).content(missingNotificationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("ACK_DLQ_PERMANENT"));
        verify(publisher).publishToDlq(any(), any(), anyString());
        verify(ingressService, never()).ingest(any());
    }
}
