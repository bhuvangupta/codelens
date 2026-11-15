package com.codelens.gate;

/**
 * The checks that make up the security merge gate. Each one is reported as its own
 * commit status so it can be marked "required" in branch protection independently.
 */
public enum GateCheck {
    DEPS("codelens/deps", "Dependency vulnerabilities"),
    SAST("codelens/sast", "Static security analysis"),
    SECRETS("codelens/secrets", "Leaked secrets");

    private final String context;
    private final String label;

    GateCheck(String context, String label) {
        this.context = context;
        this.label = label;
    }

    /** Commit status context, e.g. "codelens/secrets". Renaming it breaks branch protection rules. */
    public String context() {
        return context;
    }

    public String label() {
        return label;
    }
}
