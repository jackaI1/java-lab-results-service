package com.jacklai.labresults;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LabResult(UUID id, String patientId, String specimenId, LabTest test,
                        BigDecimal value, String unit, Instant observedAt, String status,
                        int revision, Instant updatedAt) {}
