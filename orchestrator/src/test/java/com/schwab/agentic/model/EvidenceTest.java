package com.schwab.agentic.model;

import static com.schwab.agentic.Assertions.assertEquals;
import static com.schwab.agentic.Assertions.assertThrows;

/**
 * Covers {@link Evidence}'s validation and the fields that complete the requirement to
 * criterion to evidence to gate chain: {@code acceptanceCriterionId} and {@code passed}.
 */
public class EvidenceTest {

    public void testRejectsBlankAcceptanceCriterionId() {
        assertThrows(IllegalArgumentException.class, () -> new Evidence(
            Evidence.Origin.EXECUTED, "", true, "desc", "source", "N1", null, TestFixtures.fixedInstant()),
            "Evidence must reject a blank acceptanceCriterionId");
    }

    public void testRejectsNullOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new Evidence(
            null, "AC-1", true, "desc", "source", "N1", null, TestFixtures.fixedInstant()),
            "Evidence must reject a null origin");
    }

    public void testExecutedEvidenceCarriesPassedFlag() {
        Evidence passing = new Evidence(
            Evidence.Origin.EXECUTED, "AC-1", true, "tests passed", "mvn test", "TEST", "logs/test.log",
            TestFixtures.fixedInstant());
        Evidence failing = new Evidence(
            Evidence.Origin.EXECUTED, "AC-1", false, "tests failed", "mvn test", "TEST", "logs/test.log",
            TestFixtures.fixedInstant());

        assertEquals(true, passing.passed(), "passing evidence must report passed() true");
        assertEquals(false, failing.passed(), "failing evidence must report passed() false");
        assertEquals(Evidence.Origin.EXECUTED, passing.origin(), "origin must round trip through the accessor");
    }
}
