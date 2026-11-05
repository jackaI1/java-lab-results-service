package com.jacklai.labresults;

public enum LabTest {
    GLUCOSE("14749-6", "Glucose [Moles/volume] in Serum or Plasma", "mmol/L"),
    POTASSIUM("2823-3", "Potassium [Moles/volume] in Serum or Plasma", "mmol/L"),
    HEMOGLOBIN("718-7", "Hemoglobin [Mass/volume] in Blood", "g/dL");

    private final String loinc;
    private final String display;
    private final String unit;
    LabTest(String loinc, String display, String unit) {
        this.loinc = loinc;
        this.display = display;
        this.unit = unit;
    }
    public String loinc() { return loinc; }
    public String display() { return display; }
    public String unit() { return unit; }
}
