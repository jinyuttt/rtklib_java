package org.rtklib.java.adjust.model;

@FunctionalInterface
public interface BaseDiagnosisHandler {
    void onDiagnosis(BaseStationDiagnosis diagnosis);
}