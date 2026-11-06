package com.jacklai.labresults;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

public record AuditEntry(int revision, String actor, String reason, Instant changedAt, JsonNode snapshot) {}
