package com.jacklai.labresults;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.DateTimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FhirExporterTest {
    private final FhirContext fhir = FhirContext.forR4();
    private LabResult result(LabTest test, String status, int revision) {
        return new LabResult(UUID.fromString("11111111-1111-4111-8111-111111111111"), "SYN-001", "LAB-001", test,
                new BigDecimal("5.4000"), test.unit(), Instant.parse("2026-01-15T10:30:00Z"), status,
                revision, Instant.parse("2026-01-15T11:00:00Z"));
    }
    @ParameterizedTest
    @CsvSource({"GLUCOSE,14749-6,mmol/L", "POTASSIUM,2823-3,mmol/L", "HEMOGLOBIN,718-7,g/dL"})
    void exportsRoundTrippableR4WithCorrectTestAndUnits(LabTest test, String loinc, String unit) {
        String encoded = new FhirExporter().export(result(test, "FINAL", 1));
        Observation observation = fhir.newJsonParser().parseResource(Observation.class, encoded);
        assertThat(observation.getIdElement().getIdPart()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(observation.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
        assertThat(observation.getMeta().getVersionId()).isEqualTo("1");
        assertThat(observation.getMeta().getLastUpdated().toInstant()).isEqualTo(Instant.parse("2026-01-15T11:00:00Z"));
        assertThat(observation.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("laboratory");
        assertThat(observation.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
        assertThat(observation.getCode().getCodingFirstRep().getCode()).isEqualTo(loinc);
        assertThat(observation.getValueQuantity().getValue()).isEqualByComparingTo("5.4");
        assertThat(observation.getValueQuantity().getSystem()).isEqualTo("http://unitsofmeasure.org");
        assertThat(observation.getValueQuantity().getCode()).isEqualTo(unit);
        assertThat(observation.getSubject().getIdentifier().getValue()).isEqualTo("SYN-001");
        assertThat(observation.getSpecimen().getIdentifier().getValue()).isEqualTo("LAB-001");
        assertThat(((DateTimeType) observation.getEffective()).getValue().toInstant())
                .isEqualTo(Instant.parse("2026-01-15T10:30:00Z"));
        assertThat(observation.getIssued().toInstant()).isEqualTo(Instant.parse("2026-01-15T11:00:00Z"));
    }
    @Test void exportsAmendedStatusAndRevisionAfterCorrection() {
        Observation observation = fhir.newJsonParser().parseResource(Observation.class,
                new FhirExporter().export(result(LabTest.GLUCOSE, "AMENDED", 2)));
        assertThat(observation.getStatus()).isEqualTo(Observation.ObservationStatus.AMENDED);
        assertThat(observation.getMeta().getVersionId()).isEqualTo("2");
    }
}
