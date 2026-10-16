package org.telegram.hardkey;

/**
 * Hardkey D0/D1 bootstrap — hardware-keyboard navigation for e-ink phones.
 *
 * Global toggle plus shared constants. ENABLED gates key ROUTING only;
 * observation (DriftReport) runs regardless, so drift data keeps
 * accumulating even while routing is disabled.
 */
public final class Hardkey {

    public static final String LOG_TAG = "HardkeyDrift";

    /** Master switch for key routing. Observation is never gated. */
    public static volatile boolean ENABLED = true;

    private Hardkey() {
    }
}
