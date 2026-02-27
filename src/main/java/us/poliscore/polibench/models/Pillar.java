package us.poliscore.polibench.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Pillar {
    PRECISION("Precision"),
    EVIDENCE("Evidence"),
    FEASIBILITY("Feasibility"),
    BUDGET("Budget"),
    FAIRNESS("Fairness"),
    GOVERNANCE("Governance"),
    RISK("Risk");

    private final String value;

    Pillar(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Pillar fromValue(String value) {
        for (Pillar p : values()) {
            if (p.value.equalsIgnoreCase(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown pillar: " + value);
    }
}
