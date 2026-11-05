package com.jacklai.labresults;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public record ResultRequest(
        @NotBlank @Pattern(regexp = "SYN-[A-Z0-9-]{1,32}") String patientId,
        @NotBlank @Pattern(regexp = "LAB-[A-Z0-9-]{1,32}") String specimenId,
        @NotNull LabTest test,
        @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 4) BigDecimal value,
        @NotNull @PastOrPresent Instant observedAt) {}
