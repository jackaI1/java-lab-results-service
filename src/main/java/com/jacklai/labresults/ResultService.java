package com.jacklai.labresults;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ResultService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<LabResult> row = (rs, n) -> {
        var test = LabTest.valueOf(rs.getString("test"));
        return new LabResult(rs.getObject("id", UUID.class), rs.getString("patient_id"), rs.getString("specimen_id"),
                test, rs.getBigDecimal("value"), test.unit(), rs.getTimestamp("observed_at").toInstant(),
                rs.getString("status"), rs.getInt("revision"), rs.getTimestamp("updated_at").toInstant());
    };
    ResultService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public LabResult create(ResultRequest request, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into lab_result (id, patient_id, specimen_id, test, value, observed_at, status, revision, updated_at)
                values (?, ?, ?, ?, ?, ?, 'FINAL', 1, ?)
                """, id, request.patientId(), request.specimenId(), request.test().name(), request.value(),
                Timestamp.from(request.observedAt()), Timestamp.from(Instant.now()));
        LabResult result = get(id);
        audit(result, actor, "Initial result");
        return result;
    }
    public LabResult get(UUID id) {
        return jdbc.query("select * from lab_result where id = ?", row, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Result not found"));
    }
    public List<LabResult> list(String patientId, int limit) {
        if (patientId == null) {
            return jdbc.query("select * from lab_result order by observed_at desc, id limit ?", row, limit);
        }
        return jdbc.query("select * from lab_result where patient_id = ? order by observed_at desc, id limit ?", row, patientId, limit);
    }
    @Transactional
    public LabResult correct(UUID id, CorrectionRequest request, String actor) {
        get(id); // Distinguish a missing result (404) from a stale revision (409).
        int changed = jdbc.update("""
                update lab_result set value = ?, status = 'AMENDED', revision = revision + 1, updated_at = ?
                where id = ? and revision = ?
                """, request.value(), Timestamp.from(Instant.now()), id, request.expectedRevision());
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Revision changed; read the result again before correcting it");
        }
        LabResult result = get(id);
        audit(result, actor, request.reason().strip());
        return result;
    }
    public List<AuditEntry> history(UUID id) {
        get(id);
        return jdbc.query("select * from result_audit where result_id = ? order by revision", (rs, n) ->
                new AuditEntry(rs.getInt("revision"), rs.getString("actor"), rs.getString("reason"),
                        rs.getTimestamp("changed_at").toInstant(), parse(rs.getString("snapshot"))), id);
    }
    private void audit(LabResult result, String actor, String reason) {
        try {
            jdbc.update("""
                    insert into result_audit (result_id, revision, actor, reason, changed_at, snapshot)
                    values (?, ?, ?, ?, ?, cast(? as jsonb))
                    """, result.id(), result.revision(), actor, reason, Timestamp.from(result.updatedAt()), json.writeValueAsString(result));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize audit snapshot", e); }
    }
    private com.fasterxml.jackson.databind.JsonNode parse(String snapshot) {
        try { return json.readTree(snapshot); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read audit snapshot", e); }
    }
}
