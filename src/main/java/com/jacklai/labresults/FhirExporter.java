package com.jacklai.labresults;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class FhirExporter {
    // FhirContext is expensive to initialize and safe to reuse; parsers are per request.
    private static final FhirContext FHIR = FhirContext.forR4();

    public String export(LabResult result) {
        Observation observation = new Observation();
        observation.setId(result.id().toString());
        observation.getMeta().setVersionId(Integer.toString(result.revision()))
                .setLastUpdated(Date.from(result.updatedAt()));
        observation.setStatus("AMENDED".equals(result.status())
                ? Observation.ObservationStatus.AMENDED : Observation.ObservationStatus.FINAL);
        observation.addCategory().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("laboratory").setDisplay("Laboratory");
        observation.getCode().addCoding().setSystem("http://loinc.org")
                .setCode(result.test().loinc()).setDisplay(result.test().display());
        observation.setSubject(new Reference().setType("Patient").setIdentifier(new Identifier()
                .setSystem("https://example.org/lab/synthetic-patients").setValue(result.patientId())));
        observation.setSpecimen(new Reference().setType("Specimen").setIdentifier(new Identifier()
                .setSystem("https://example.org/lab/synthetic-specimens").setValue(result.specimenId())));
        observation.setEffective(new DateTimeType(Date.from(result.observedAt())));
        observation.setIssued(Date.from(result.updatedAt()));
        observation.setValue(new Quantity().setValue(result.value()).setUnit(result.unit())
                .setSystem("http://unitsofmeasure.org").setCode(result.unit()));
        return FHIR.newJsonParser().setPrettyPrint(true).encodeResourceToString(observation);
    }
}
