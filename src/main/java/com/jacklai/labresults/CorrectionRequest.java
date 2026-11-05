package com.jacklai.labresults;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CorrectionRequest(
        @NotNull @Min(1) Integer expectedRevision,
        @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 4) BigDecimal value,
        @NotBlank @Size(max = 500) String reason) {}
