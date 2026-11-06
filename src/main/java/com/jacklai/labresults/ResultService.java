package com.jacklai.labresults;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

@Service
public class ResultService {
    private final DataSource pool;
    private final ObjectMapper json;
    private final boolean mysql;
    private final boolean sqlserver;

    ResultService(DataSource pool, ObjectMapper json, @Value("${lab.database:postgresql}") String database) {
        this.pool = pool;
        this.json = json;
        if (!List.of("postgresql", "mysql", "sqlserver").contains(database)) {
            throw new IllegalArgumentException("Unsupported database profile");
        }
        this.mysql = database.equals("mysql");
        this.sqlserver = database.equals("sqlserver");
    }

    @Transactional
    public LabResult create(ResultRequest request, String actor) {
        UUID id = UUID.randomUUID();
        update("""
                insert into lab_result (id, patient_id, specimen_id, test, value, observed_at, status, revision, updated_at)
                values (?, ?, ?, ?, ?, ?, 'FINAL', 1, ?)
                """, id, request.patientId(), request.specimenId(), request.test().name(), request.value(),
                Timestamp.from(request.observedAt()), Timestamp.from(Instant.now()));
        LabResult result = get(id);
        audit(result, actor, "Initial result");
        return result;
    }

    public LabResult get(UUID id) {
        return query("select * from lab_result where id = ?", this::readResult, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Result not found"));
    }

    public List<LabResult> list(String patientId, int limit) {
        if (sqlserver) {
            if (patientId == null) return query("select * from lab_result order by observed_at desc, id offset 0 rows fetch next ? rows only", this::readResult, limit);
            return query("select * from lab_result where patient_id = ? order by observed_at desc, id offset 0 rows fetch next ? rows only", this::readResult, patientId, limit);
        }
        if (patientId == null) {
            return query("select * from lab_result order by observed_at desc, id limit ?", this::readResult, limit);
        }
        return query("select * from lab_result where patient_id = ? order by observed_at desc, id limit ?",
                this::readResult, patientId, limit);
    }

    @Transactional
    public LabResult correct(UUID id, CorrectionRequest request, String actor) {
        get(id); // Distinguish a missing result (404) from a stale revision (409).
        int changed = sqlserver ? query("exec revise_result ?, ?, ?, ?", rs -> rs.getInt(1), id, request.expectedRevision(), request.value(), Timestamp.from(Instant.now())).getFirst() : update("""
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
        return query("select * from result_audit where result_id = ? order by revision", rs ->
                new AuditEntry(rs.getInt("revision"), rs.getString("actor"), rs.getString("reason"),
                        rs.getTimestamp("changed_at", utc()).toInstant(), parse(rs.getString("snapshot"))), id);
    }

    private LabResult readResult(ResultSet rs) throws SQLException {
        var test = LabTest.valueOf(rs.getString("test"));
        return new LabResult(UUID.fromString(rs.getString("id")), rs.getString("patient_id"), rs.getString("specimen_id"),
                test, rs.getBigDecimal("value"), test.unit(), rs.getTimestamp("observed_at", utc()).toInstant(),
                rs.getString("status"), rs.getInt("revision"), rs.getTimestamp("updated_at", utc()).toInstant());
    }

    private void audit(LabResult result, String actor, String reason) {
        try {
            String snapshot = sqlserver ? "?" : mysql ? "cast(? as json)" : "cast(? as jsonb)";
            update("insert into result_audit (result_id, revision, actor, reason, changed_at, snapshot) "
                            + "values (?, ?, ?, ?, ?, " + snapshot + ")", result.id(), result.revision(), actor,
                    reason, Timestamp.from(result.updatedAt()), json.writeValueAsString(result));
        } catch (JacksonException e) { throw new IllegalStateException("Cannot serialize audit snapshot", e); }
    }

    private int update(String sql, Object... parameters) {
        return execute(sql, PreparedStatement::executeUpdate, parameters);
    }

    private <T> List<T> query(String sql, RowReader<T> reader, Object... parameters) {
        return execute(sql, statement -> {
            try (ResultSet rs = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rs.next()) results.add(reader.read(rs));
                return results;
            }
        }, parameters);
    }

    private <T> T execute(String sql, StatementWork<T> work, Object... parameters) {
        // Never close a transaction-bound connection here: Spring commits/rolls it back first.
        Connection connection = DataSourceUtils.getConnection(pool);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                Object value = parameters[i];
                if (value instanceof UUID id) {
                    if (mysql || sqlserver) statement.setString(i + 1, id.toString());
                    else statement.setObject(i + 1, id);
                } else if (value instanceof Timestamp timestamp) {
                    statement.setTimestamp(i + 1, timestamp, utc());
                } else {
                    statement.setObject(i + 1, value);
                }
            }
            return work.run(statement);
        } catch (SQLException e) {
            throw new UncategorizedSQLException("Lab result storage", sql, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, pool);
        }
    }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    @FunctionalInterface private interface RowReader<T> { T read(ResultSet rs) throws SQLException; }
    @FunctionalInterface private interface StatementWork<T> { T run(PreparedStatement statement) throws SQLException; }

    private tools.jackson.databind.JsonNode parse(String snapshot) {
        try { return json.readTree(snapshot); }
        catch (JacksonException e) { throw new IllegalStateException("Cannot read audit snapshot", e); }
    }
}
