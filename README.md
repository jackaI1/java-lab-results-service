# Lab results service

I built a small service for lab measurements because I wanted a way to correct a reading without losing the original value. It keeps the latest result, who changed it and why, and every earlier version together. I can keep those records in PostgreSQL or MySQL, using the same requests and history rules. I use invented patient and sample identifiers throughout.

There is no screen to click through. I send it JSON, a plain text format for named values, with commands like the ones below. A reader can look up measurements and their history. A technician can also record a measurement or correct one. I can export a result in FHIR, a standard format for exchanging health records.

## Try it locally

I use Docker with Compose to run the service and its database together. Install Docker Desktop or another running Docker engine with the Compose plugin. The walkthrough script also needs Python 3 and OpenSSL, which the setup script uses to generate local passwords.

```sh
git clone https://github.com/jackaI1/java-lab-results-service.git
cd java-lab-results-service
./scripts/setup-demo.sh
docker compose up --build --wait
./scripts/demo.sh
```

The first build downloads dependencies and may take a few minutes. The script exercises recording, reading, correction, history and export, including requests that should be refused. The service listens at `http://127.0.0.1:8080`. Compose loads one synthetic glucose result with ID `11111111-1111-4111-8111-111111111111`.

## Choose MySQL instead

I added a second database option so I can run the same measurement workflow where MySQL is already available. PostgreSQL remains the default. For a separate MySQL 8.4 demo, I use the same setup script and add an override file, which replaces the database container and selects the matching app settings:

```sh
./scripts/setup-demo.sh
docker compose -p lab-mysql -f compose.yaml -f compose.mysql.yaml up --build --wait
./scripts/demo.sh
```

Stop the PostgreSQL demo first if it uses port 8080. Both options load the same invented example and accept all the commands below. Each keeps its own database volume. To stop MySQL while keeping its records:

```sh
docker compose -p lab-mysql -f compose.yaml -f compose.mysql.yaml down
```

Add `-v` to that last command only when I want to erase the MySQL demo. Switching options selects a different database; it does not transfer existing records between databases.

## Read and correct a result

The setup script saves randomly generated passwords in `.env`, which Git ignores. These commands load that local file without displaying the passwords:

```sh
set -a
. ./.env
set +a

curl --fail-with-body -u "reader:$READER_PASSWORD" \
  http://127.0.0.1:8080/api/results/11111111-1111-4111-8111-111111111111
```

A result includes a `revision`, its version number. I send the version I just read with a correction so an older copy cannot overwrite someone else's change:

```sh
curl --fail-with-body -u "technician:$TECHNICIAN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":1,"value":5.9,"reason":"Rechecked synthetic instrument reading"}' \
  http://127.0.0.1:8080/api/results/11111111-1111-4111-8111-111111111111/corrections

curl --fail-with-body -u "reader:$READER_PASSWORD" \
  http://127.0.0.1:8080/api/results/11111111-1111-4111-8111-111111111111/history

curl --fail-with-body -u "reader:$READER_PASSWORD" \
  http://127.0.0.1:8080/api/results/11111111-1111-4111-8111-111111111111/fhir
```

The history keeps the original 5.4 and the corrected 5.9, along with the account, reason and time. Repeating that correction returns `409 Conflict`; read the latest revision before changing it again. The export reflects the current value and marks a corrected result as amended.

To add a separate result:

```sh
curl --fail-with-body -u "technician:$TECHNICIAN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"patientId":"SYN-002","specimenId":"LAB-002","test":"POTASSIUM","value":4.1,"observedAt":"2026-01-15T10:30:00Z"}' \
  http://127.0.0.1:8080/api/results
```

I copy the returned `id` into the same read, correction, history and export routes. I can list recent results with `/api/results?patientId=SYN-002&limit=20`. The optional limit is 1 to 100, with 50 as the default, ordered by observation time from newest to oldest.

## What I accept

| Test name in a request | Measurement | Fixed unit | LOINC test code |
| --- | --- | --- | --- |
| `GLUCOSE` | Glucose in serum or plasma | mmol/L | 14749-6 |
| `POTASSIUM` | Potassium in serum or plasma | mmol/L | 2823-3 |
| `HEMOGLOBIN` | Hemoglobin in blood | g/dL | 718-7 |

LOINC provides standard identifiers for tests. The export uses UCUM, standard codes for measurement units. I keep each test and unit paired in the service rather than trusting a unit supplied by a caller.

Patient identifiers must start with `SYN-` and specimen identifiers with `LAB-`, followed by 1 to 32 uppercase letters, digits or hyphens. Values must be from 0 to 1,000,000 with up to four decimal places, and the observation time cannot be in the future. These are input and storage checks, not clinical reference ranges. Invalid input returns `400`, missing results return `404`, absent or incorrect credentials return `401`, and a reader trying to change data gets `403`.

## Stop or reset

```sh
docker compose down       # stop; keep the measurements
docker compose down -v    # stop and erase this demo's database
```

If port 8080 is occupied, use `APP_PORT=18080 docker compose up --build --wait`, then `APP_PORT=18080 ./scripts/demo.sh`. Use that port in the curl commands too. If the build cannot connect to Docker, start the Docker engine first. Keep `.env` while keeping the database volume: generating new database credentials does not change an existing database password.

## How I put it together

I use Java 21 and Spring Boot for the REST API, an interface built around HTTP requests. Spring Security checks the reader and technician roles on each request. PostgreSQL or MySQL stores the results. Flyway applies numbered SQL migrations, the files that create and update the database structure. Each database has its own SQL files.

`ResultService` uses plain JDBC, Java's database interface, with prepared statements that keep supplied values separate from SQL commands. HikariCP keeps up to five reusable database connections in a pool. Spring binds a pooled connection to each transaction; statements and result sets close after use, and the connection returns to the pool. A correction updates the current value only when the expected revision matches, then adds a complete audit snapshot in the same transaction. If storing the history fails, the value change rolls back. A database trigger rejects updates and deletes of history entries. HAPI FHIR serializes the current result as an R4 Observation with its test, quantity, subject and sample identifiers, timestamps and revision.

The `lab_result` table holds the latest measurement: its ID, invented patient and specimen IDs, test, decimal value, observation time, status, revision and last update time. `result_audit` holds the result ID and revision as a combined key, the account, reason, time and complete JSON snapshot. Both databases enforce value ranges and identifier formats and index recent results by patient and observation time. MySQL uses InnoDB tables for transactions, text UUIDs for IDs, UTC date/time columns with six decimal places, and native JSON snapshots; PostgreSQL keeps its native UUID, time-zone-aware timestamp and JSONB columns.

For an existing database, I create an empty `labresults` database and provide its address and credentials through environment variables. I leave the `demo` profile off so no example rows are inserted:

```sh
# Set DB_USER, DB_PASSWORD, READER_PASSWORD and TECHNICIAN_PASSWORD locally first.
SPRING_PROFILES_ACTIVE=mysql DB_URL=jdbc:mysql://localhost:3306/labresults ./mvnw spring-boot:run
# PostgreSQL: omit SPRING_PROFILES_ACTIVE and use DB_URL=jdbc:postgresql://localhost:5432/labresults
```

The MySQL profile sets the connection time zone to UTC. I preserve instants when reading and writing timestamps, including when Java runs in a different time zone. The migration account needs table, index and trigger creation permissions for Flyway. MySQL 8.4 enables binary logging by default, so an administrator must also permit trigger creation: the local Compose demo and tests set `log_bin_trust_function_creators=1`. On an existing server I would ask its administrator to choose that setting or run the migrations with an appropriately privileged account, as described in [MySQL's trigger permissions](https://dev.mysql.com/doc/refman/8.4/en/create-trigger.html). A separate restricted runtime account would be a deployment improvement.

I explain the request flow and tradeoffs in [docs/design.md](docs/design.md). The extra synthetic migration runs only with the `demo` profile used by Compose. I keep that profile's database separate from databases created without demo data.

## Limits I would address before real use

This is a local demonstration with synthetic data, not a system for patient care. Identifier prefixes cannot guarantee that someone has avoided personal data. The two shared accounts identify demo roles rather than individual people. HTTP Basic sends credentials on each request, so I would require TLS and individual accounts from an identity provider before deployment. I would also need authorization by organization or patient, operational monitoring, backups and retention rules.

A database administrator can disable the audit trigger. The FHIR output is an R4 resource export, not a complete FHIR server or proof of conformance to a particular clinical profile. The bounded recent-results list does not page through an entire database.

## Build and checks

For development I need a Java 21 JDK and a running Docker engine. The Maven wrapper downloads Maven, so a separate Maven installation is optional:

```sh
./mvnw -B verify
```

On macOS I can use a dedicated Colima profile. The explicit socket keeps the tests pointed at that profile:

```sh
colima start --profile lab-results --cpu 2 --memory 3 --vm-type vz
export DOCKER_HOST="unix://$HOME/.colima/lab-results/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw -B verify
colima stop --profile lab-results
```

JUnit runs 50 tests: the same 23 integration cases on real PostgreSQL 17 and MySQL 8.4 containers, plus four FHIR tests. Each integration run applies the actual Flyway migrations. They cover access rules, invalid input, persistence, concurrent and stale corrections, audit immutability, rollback of both creation and correction after an audit failure, SQL metacharacters, timestamp and decimal precision, returned pool connections, and HAPI FHIR round trips. Docker is required; the database tests are not silently skipped. The demo script adds 11 HTTP checks against the packaged app. GitHub Actions runs separate PostgreSQL and MySQL jobs on pull requests and on `main`. Each packages the app and runs the 11-check Compose walkthrough for its database, then removes its containers and volumes.
