# Recording and correcting lab results

I keep a result's current value separate from its history so a correction does not erase the original measurement. This project accepts synthetic records only. It is a learning service, not a system for patient care.

## Request flow

Spring Security checks an HTTP Basic username and password on every request. The `reader` account can retrieve results, history and exports. The `technician` account can also create and correct results. Passwords come from the environment and are encoded in memory with BCrypt. There is no registration route or shared default password.

Spring MVC accepts JSON, and Bean Validation checks the fields before the service touches the database. The test catalog fixes the LOINC code (a standard test identifier) and UCUM unit (a standard measurement unit), so callers cannot pair glucose with the wrong unit. Values must be nonnegative, at most 1,000,000 and have at most four decimal places. Those are storage limits, not clinical reference ranges. Patient and specimen identifiers must begin with `SYN-` and `LAB-`; these formats do not detect all personal data, so only invented records belong here.

The service uses plain JDBC prepared statements through Java's `Connection`, `PreparedStatement` and `ResultSet` interfaces. HikariCP supplies a bounded connection pool. `DataSourceUtils` acquires and releases the Spring transaction's connection, while try-with-resources closes each statement and result set. Flyway applies numbered SQL files to create the database. Each creation inserts a current result and revision 1 of its audit history in one transaction. Each correction includes the revision the caller last read and a reason. An SQL update succeeds only if that revision is still current. A stale caller gets HTTP 409 and must read again. Two simultaneous corrections cannot silently overwrite each other.

An audit entry includes the complete result after that change, the authenticated account, the reason, and the server timestamp. Database triggers in PostgreSQL and MySQL reject updates and deletes of audit entries. If storing the audit entry fails, the transaction rolls back the result change too. A database owner can still disable triggers, so this is not tamper-proof evidence against a database administrator. API clients have no delete route.

## Export

HAPI FHIR builds an R4 Observation, the FHIR resource for a measurement. The export contains the result ID, revision, final or amended status, laboratory category, LOINC test, quantity with UCUM units, synthetic subject and specimen identifiers, observation time and last change time. It represents the current revision. The history route contains earlier snapshots. No request is sent to a remote FHIR server, and no Patient resource or clinical profile conformance is claimed.

## Boundaries

Listing is newest observation first with a stable ID tie-breaker, an optional exact patient filter, and a limit from 1 to 100. This is a bounded recent-results view, not a full pagination API. Each history belongs to one result. Demo data is loaded only through the `demo` profile's extra Flyway location. The ordinary profile starts without sample records.

HTTP Basic is convenient for a local demonstration. Before any real deployment I would use TLS, individual accounts from an identity provider, narrower authorization by organization or patient, retention policies, rate limits, backups and operational monitoring. A shared role account identifies a role in the demo, not an individual technician. These are documented limits, not implemented safeguards.

## Test code references

I checked the catalog against the published LOINC entries for [glucose in mmol/L](https://loinc.org/14749-6), [potassium](https://loinc.org/2823-3) and [hemoglobin](https://loinc.org/718-7). Glucose in mmol/L needs the moles-per-volume code 14749-6; the mass-per-volume code 2345-7 belongs to a different quantity. The export test pins these mappings independently of the enum.

## Two storage engines

The default Flyway location remains `db/migration`, and its PostgreSQL V1 file is unchanged, so existing Flyway checksums remain valid. The `mysql` profile selects `db/mysql` and MySQL Connector/J. The `demo` profile adds the matching vendor's seed directory (`db/demo` or `db/mysql-demo`). These profiles choose schema dialects; they do not copy records between engines.

MySQL 8.4 uses InnoDB, `CHAR(36)` UUIDs, `DECIMAL(11,4)`, `DATETIME(6)` and `JSON`. ASCII binary collations preserve case-sensitive identifiers and enums. The identifier checks use a character whitelist and an independent length bound. Separate BEFORE UPDATE and BEFORE DELETE triggers signal an error for audit changes. MySQL binary logging adds a trigger-creation privilege requirement; the local containers set `log_bin_trust_function_creators=1`, and the README explains the administrator prerequisite on an existing instance. The driver session and JDBC timestamp calendars use UTC; DATETIME avoids TIMESTAMP's 2038 ceiling. The UTC connection settings follow [Connector/J's time-instant guidance](https://dev.mysql.com/doc/connectors/en/connector-j-time-instants.html).

The Java service shares every query except the audit JSON cast and UUID binding. Its dialect is derived from the configured database profile, never from submitted values. IDs and all request values are bound parameters. The SQL revision condition lets the database choose exactly one successful correction when callers race; both the update and new audit snapshot use the same transaction-bound pooled connection. SQL failures become Spring data-access exceptions, which trigger rollback and map to a generic HTTP 500 without exposing submitted content.

The integration contract runs unchanged on PostgreSQL and MySQL, including simultaneous corrections, stale callers, immutable audit entries and a targeted constraint that forces an audit failure. That constraint is always removed in a finally block. Failed creation, missing records and repeated conflicts also exercise pool release. CI runs each engine separately, then exercises the packaged app with its own Compose demo.
