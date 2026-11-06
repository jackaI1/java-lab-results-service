package com.jacklai.labresults;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "lab.security.reader-password=test-reader-password",
        "lab.security.technician-password=test-technician-password"
})
@AutoConfigureMockMvc
abstract class ResultStorageContract {
    abstract Object databaseId(String id);
    String dropConstraint() { return "drop constraint"; }
    @Autowired ResultService service;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private static final String VALID = """
        {"patientId":"SYN-001","specimenId":"LAB-001","test":"GLUCOSE",
         "value":5.4,"observedAt":"2026-01-15T10:30:00Z"}
        """;
    private JsonNode create(String body) throws Exception {
        var response = mvc.perform(post("/api/results").with(httpBasic("technician", "test-technician-password"))
                .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }
    private JsonNode create() throws Exception { return create(VALID); }
    private String correction(int revision, String value, String reason) {
        return "{\"expectedRevision\":" + revision + ",\"value\":" + value + ",\"reason\":\"" + reason + "\"}";
    }
    private int correct(String id, String body) throws Exception {
        return mvc.perform(post("/api/results/{id}/corrections", id)
                .with(httpBasic("technician", "test-technician-password"))
                .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
    }
    @Test void fhirEndpointRequiresAuthenticationAndExportsCurrentRevision() throws Exception {
        String id = create().get("id").asText();
        mvc.perform(get("/api/results/{id}/fhir", id)).andExpect(status().isUnauthorized());
        assertThat(correct(id, correction(1, "5.9", "Recalibrated instrument"))).isEqualTo(200);
        mvc.perform(get("/api/results/{id}/fhir", id).with(httpBasic("reader", "test-reader-password")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("Observation"))
                .andExpect(jsonPath("$.status").value("amended"))
                .andExpect(jsonPath("$.meta.versionId").value("2"))
                .andExpect(jsonPath("$.valueQuantity.value").value(5.9));
        mvc.perform(get("/api/results/{id}/fhir", UUID.randomUUID()).with(httpBasic("reader", "test-reader-password")))
                .andExpect(status().isNotFound());
    }
    @Test void requiresAuthenticationAndRejectsWrongPassword() throws Exception {
        mvc.perform(get("/api/results")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/results").with(httpBasic("reader", "wrong"))).andExpect(status().isUnauthorized());
    }
    @Test void readerCanReadButCannotCreateCorrectOrDelete() throws Exception {
        String id = create().get("id").asText();
        for (String path : List.of("/api/results", "/api/results/" + id, "/api/results/" + id + "/history")) {
            mvc.perform(get(path).with(httpBasic("reader", "test-reader-password"))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/results").with(httpBasic("reader", "test-reader-password"))
                .contentType("application/json").content(VALID)).andExpect(status().isForbidden());
        mvc.perform(post("/api/results/{id}/corrections", id).with(httpBasic("reader", "test-reader-password"))
                .contentType("application/json").content(correction(1, "5.9", "Recalibrated"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/results/{id}", id).with(httpBasic("technician", "test-technician-password")))
                .andExpect(status().isForbidden());
    }
    @Test void recordsResultAndFiltersWithoutMixingPatients() throws Exception {
        JsonNode created = create();
        String id = created.get("id").asText();
        assertThat(created.get("revision").asInt()).isEqualTo(1);
        assertThat(created.get("status").asText()).isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("select value from lab_result where id = ?", Double.class, databaseId(id))).isEqualTo(5.4);
        create(VALID.replace("SYN-001", "SYN-OTHER"));
        var response = mvc.perform(get("/api/results").param("patientId", "SYN-OTHER").param("limit", "1")
                .with(httpBasic("reader", "test-reader-password"))).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode list = json.readTree(response.getContentAsString());
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("patientId").asText()).isEqualTo("SYN-OTHER");
        mvc.perform(get("/api/results/{id}", id).with(httpBasic("reader", "test-reader-password")))
                .andExpect(jsonPath("$.value").value(5.4)).andExpect(jsonPath("$.unit").value("mmol/L"));
    }
    @Test void correctionPreservesOriginalAndActorAndRejectsStaleRevision() throws Exception {
        String id = create().get("id").asText();
        assertThat(correct(id, correction(1, "5.9", "Recalibrated instrument"))).isEqualTo(200);
        assertThat(correct(id, correction(1, "6.1", "Stale change"))).isEqualTo(409);
        var response = mvc.perform(get("/api/results/{id}/history", id).with(httpBasic("reader", "test-reader-password")))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode history = json.readTree(response.getContentAsString());
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("snapshot").get("value").decimalValue()).isEqualByComparingTo("5.4");
        assertThat(history.get(0).get("actor").asText()).isEqualTo("technician");
        assertThat(history.get(1).get("snapshot").get("value").decimalValue()).isEqualByComparingTo("5.9");
        assertThat(history.get(1).get("reason").asText()).isEqualTo("Recalibrated instrument");
        mvc.perform(get("/api/results/{id}", id).with(httpBasic("reader", "test-reader-password")))
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.status").value("AMENDED"));
    }
    @Test void concurrentCorrectionsHaveExactlyOneWinner() throws Exception {
        String id = create().get("id").asText();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> calls = List.of(() -> correct(id, correction(1, "5.8", "First correction")),
                    () -> correct(id, correction(1, "5.9", "Second correction")));
            var statuses = executor.invokeAll(calls);
            assertThat(List.of(statuses.get(0).get(), statuses.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from result_audit where result_id = ?", Integer.class, databaseId(id))).isEqualTo(2);
    }
    @Test void auditCannotBeRewrittenOrDeleted() throws Exception {
        Object id = databaseId(create().get("id").asText());
        assertThatThrownBy(() -> jdbc.update("update result_audit set actor = 'other' where result_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from result_audit where result_id = ?", id)).hasMessageContaining("append-only");
    }
    @Test void failedAuditInsertRollsBackCorrection() throws Exception {
        Object id = databaseId(create().get("id").asText());
        // A targeted DB constraint simulates an audit storage failure for this result only.
        jdbc.execute("alter table result_audit add constraint reject_test_correction check (result_id <> '" + id + "' or revision = 1)");
        try {
            assertThat(correct(id.toString(), correction(1, "5.9", "Recalibrated"))).isEqualTo(500);
            assertThat(jdbc.queryForObject("select revision from lab_result where id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select value from lab_result where id = ?", Double.class, id)).isEqualTo(5.4);
            assertThat(jdbc.queryForObject("select revision from lab_result where id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from result_audit where result_id = ?", Integer.class, id)).isEqualTo(1);
        } finally { jdbc.execute("alter table result_audit " + dropConstraint() + " reject_test_correction"); }
    }
    @Test void failedAuditInsertAlsoRollsBackCreation() {
        int before = jdbc.queryForObject("select count(*) from lab_result", Integer.class);
        assertThatThrownBy(() -> service.create(new ResultRequest("SYN-ROLLBACK", "LAB-ROLLBACK",
                LabTest.GLUCOSE, new java.math.BigDecimal("5.4"), java.time.Instant.parse("2026-01-15T10:30:00Z")),
                "x".repeat(101))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from lab_result", Integer.class)).isEqualTo(before);
    }
    @Test void metacharactersRemainDataAndFailedRequestsReturnPoolConnections() throws Exception {
        String id = create().get("id").asText();
        String reason = "Rechecked '); DELETE FROM lab_result; --";
        assertThat(correct(id, correction(1, "5.9", reason))).isEqualTo(200);
        assertThat(service.history(UUID.fromString(id)).get(1).reason()).isEqualTo(reason);
        assertThat(service.list("SYN-001' OR '1'='1", 10)).isEmpty();
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> service.correct(UUID.fromString(id),
                    new CorrectionRequest(1, new java.math.BigDecimal("6.1"), "Stale"), "technician"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(service.get(UUID.fromString(id)).revision()).isEqualTo(2);
        var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isLessThanOrEqualTo(5);
    }
    @Test void decimalsAndInstantsSurviveDatabaseAndAuditRoundTrip() throws Exception {
        JsonNode created = create(VALID.replace("5.4", "123.4567")
                .replace("2026-01-15T10:30:00Z", "2026-01-15T05:30:00.123456-05:00"));
        var result = service.get(UUID.fromString(created.get("id").asText()));
        assertThat(result.value()).isEqualByComparingTo("123.4567");
        assertThat(result.observedAt()).isEqualTo(java.time.Instant.parse("2026-01-15T10:30:00.123456Z"));
        assertThat(service.history(result.id()).getFirst().snapshot().get("observedAt").asText())
                .isEqualTo("2026-01-15T10:30:00.123456Z");
    }
    @Test void databaseRejectsPaddedAndLowercaseCatalogValues() throws Exception {
        Object id = databaseId(create().get("id").asText());
        for (String invalid : List.of("GLUCOSE ", "glucose", "UNKNOWN")) {
            assertThatThrownBy(() -> jdbc.update("update lab_result set test = ? where id = ?", invalid, id))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        for (String invalid : List.of("FINAL ", "final", "UNKNOWN")) {
            assertThatThrownBy(() -> jdbc.update("update lab_result set status = ? where id = ?", invalid, id))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    @Test void databaseRejectsInvalidIdentifiersEvenWithoutApiValidation() throws Exception {
        Object id = databaseId(create().get("id").asText());
        for (String invalid : List.of("syn-lowercase", "SYN-contains space", "SYN-001\n")) {
            assertThatThrownBy(() -> jdbc.update("update lab_result set patient_id = ? where id = ?", invalid, id))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"-1", "1000001", "5.12345", "null"})
    void rejectsInvalidQuantities(String value) throws Exception {
        mvc.perform(post("/api/results").with(httpBasic("technician", "test-technician-password"))
                .contentType("application/json").content(VALID.replace("5.4", value)))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"SYN-001|Alex", "", "Real Person"})
    void restrictsPatientIdentifiersToSyntheticFormat(String patient) throws Exception {
        mvc.perform(post("/api/results").with(httpBasic("technician", "test-technician-password"))
                .contentType("application/json").content(VALID.replace("SYN-001", patient))).andExpect(status().isBadRequest());
    }
    @Test void rejectsFutureTimeUnknownTestMalformedJsonAndUnknownProperties() throws Exception {
        for (String body : List.of(VALID.replace("2026-01-15", "2099-01-15"), VALID.replace("GLUCOSE", "UNKNOWN"), VALID.replace("\"GLUCOSE\"", "0"),
                "{", VALID.replace("\"value\":5.4", "\"value\":5.4,\"unit\":\"wrong\""))) {
            mvc.perform(post("/api/results").with(httpBasic("technician", "test-technician-password"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }
    @Test void validatesCorrectionsAndListBounds() throws Exception {
        String id = create().get("id").asText();
        for (String body : List.of(correction(0, "5.9", "Reason"), correction(1, "5.9", " "), correction(1, "-1", "Reason"))) {
            assertThat(correct(id, body)).isEqualTo(400);
        }
        for (String limit : List.of("0", "101", "invalid")) {
            mvc.perform(get("/api/results").param("limit", limit).with(httpBasic("reader", "test-reader-password")))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test void missingResultsAndMalformedIdsHaveExplicitErrors() throws Exception {
        for (String suffix : List.of("", "/history")) {
            mvc.perform(get("/api/results/" + UUID.randomUUID() + suffix).with(httpBasic("reader", "test-reader-password")))
                    .andExpect(status().isNotFound());
        }
        assertThat(correct(UUID.randomUUID().toString(), correction(1, "5.9", "Reason"))).isEqualTo(404);
        mvc.perform(get("/api/results/not-a-uuid").with(httpBasic("reader", "test-reader-password")))
                .andExpect(status().isBadRequest());
    }
}
