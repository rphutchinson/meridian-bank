package com.meridianbank.annotation;

/**
 * Degree to which the migration framework can automate the transformation.
 */
public enum AutomationLevel {

    /** Agent can transform with high confidence — no human review required */
    FULL,

    /** Agent generates a candidate transformation — human review recommended */
    PARTIAL,

    /** Agent provides scaffolding and guidance — human writes the final code */
    MANUAL
}
