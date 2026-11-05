package com.jacklai.labresults;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ResultController {
    private final ResultService results;
    private final FhirExporter fhir;
    ResultController(ResultService results, FhirExporter fhir) { this.results = results; this.fhir = fhir; }

    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "up"); }

    @PostMapping("/api/results")
    ResponseEntity<LabResult> create(@Valid @RequestBody ResultRequest request, Principal principal) {
        var result = results.create(request, principal.getName());
        return ResponseEntity.created(URI.create("/api/results/" + result.id())).body(result);
    }
    @GetMapping("/api/results")
    List<LabResult> list(@RequestParam(required = false) @Pattern(regexp = "SYN-[A-Z0-9-]{1,32}") String patientId,
                         @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return results.list(patientId, limit);
    }
    @GetMapping("/api/results/{id}")
    LabResult get(@PathVariable UUID id) { return results.get(id); }

    @PostMapping("/api/results/{id}/corrections")
    LabResult correct(@PathVariable UUID id, @Valid @RequestBody CorrectionRequest request, Principal principal) {
        return results.correct(id, request, principal.getName());
    }
    @GetMapping(value = "/api/results/{id}/fhir", produces = "application/fhir+json")
    String export(@PathVariable UUID id) { return fhir.export(results.get(id)); }

    @GetMapping("/api/results/{id}/history")
    List<AuditEntry> history(@PathVariable UUID id) { return results.history(id); }
}
