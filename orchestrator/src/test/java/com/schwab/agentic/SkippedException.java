package com.schwab.agentic;

/**
 * Thrown by a test to report that it could not run in this environment, distinct from
 * either passing or failing. A test that silently returns without asserting anything
 * when its precondition is missing (for example a live-API test with no key exported)
 * is indistinguishable from a test that ran and found nothing wrong, which is exactly
 * the failure mode this whole project treats as disqualifying: a canned success that
 * looks like coverage but proves nothing. Throwing SkippedException makes the
 * distinction explicit and visible in {@link TestRunner}'s summary line, instead of
 * hidden inside a method body that returns early.
 */
public final class SkippedException extends RuntimeException {
    public SkippedException(String reason) {
        super(reason);
    }
}
