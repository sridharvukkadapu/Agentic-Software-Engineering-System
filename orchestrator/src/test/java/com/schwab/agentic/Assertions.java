package com.schwab.agentic;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A minimal assertion library, standing in for JUnit, which the orchestrator is not
 * allowed to depend on. Without this, every test would need its own ad hoc comparison
 * and error message logic, and failures would be inconsistent in what they report,
 * making it harder to tell at a glance what actually went wrong.
 */
public final class Assertions {

    private Assertions() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    public static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + ": expected null but was <" + value + ">");
        }
    }

    /**
     * Asserts that invoking {@code action} throws an instance of {@code expectedType}.
     * Returns the thrown exception so the caller can assert on its message too.
     */
    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return expectedType.cast(actual);
            }
            throw new AssertionError(
                message + ": expected " + expectedType.getName()
                    + " but got " + actual.getClass().getName() + ": " + actual.getMessage(), actual);
        }
        throw new AssertionError(message + ": expected " + expectedType.getName() + " but nothing was thrown");
    }

    public static void assertDoesNotThrow(Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable t) {
            throw new AssertionError(message + ": expected no exception but got " + t, t);
        }
    }

    /** Fails immediately with the given message, useful for marking unreachable branches. */
    public static void fail(String message) {
        throw new AssertionError(message);
    }

    /** Fails immediately, building the message lazily so it is only computed on failure. */
    public static void fail(Supplier<String> messageSupplier) {
        throw new AssertionError(messageSupplier.get());
    }
}
