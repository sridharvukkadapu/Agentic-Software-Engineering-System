package com.schwab.agentic.model;

import static com.schwab.agentic.Assertions.assertFalse;
import static com.schwab.agentic.Assertions.assertThrows;
import static com.schwab.agentic.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Covers the structural guarantee behind CLAUDE.md rule 1: {@link AuditEvent} cannot be
 * constructed from outside {@link WorkflowState}'s package, and its own validation
 * rejects a STATUS_CHANGE event missing from/to or a non-STATUS_CHANGE event that
 * illegitimately carries them.
 */
public class AuditEventTest {

    public void testConstructorIsPackagePrivateNotPublic() throws Exception {
        Constructor<AuditEvent> constructor = canonicalConstructor();
        assertFalse(Modifier.isPublic(constructor.getModifiers()),
            "AuditEvent constructor must not be public, only WorkflowState may build one");
    }

    public void testStatusChangeEventRequiresFromAndTo() {
        assertThrows(IllegalArgumentException.class,
            () -> constructViaReflection(1L, "RUN-1", "N1", AuditEvent.EventType.STATUS_CHANGE,
                null, null, "system", "reason", Map.of(), Instant.now()),
            "a STATUS_CHANGE event missing from/to must be rejected");
    }

    public void testNonStatusChangeEventRejectsFromOrTo() {
        assertThrows(IllegalArgumentException.class,
            () -> constructViaReflection(1L, "RUN-1", null, AuditEvent.EventType.AGENT_CALL,
                NodeStatus.PENDING, NodeStatus.RUNNING, "system", "reason", Map.of(), Instant.now()),
            "a non-STATUS_CHANGE event carrying from/to must be rejected");
    }

    public void testToLogLineContainsSequenceTypeAndActor() {
        WorkflowState state = new WorkflowState("RUN-1", TestFixtures.requirementSpec(),
            List.of(TestFixtures.node("N1")));
        state.transition(state.getNode("N1"), NodeStatus.RUNNING, "agent:implementer", "starting");
        AuditEvent event = state.getAuditLog().get(0);

        String line = event.toLogLine();
        assertTrue(line.contains("seq=1"), "log line must contain sequence: " + line);
        assertTrue(line.contains("STATUS_CHANGE"), "log line must contain type: " + line);
        assertTrue(line.contains("agent:implementer"), "log line must contain actor: " + line);
        assertTrue(line.contains("PENDING -> RUNNING"), "log line must contain the transition: " + line);
    }

    private static Constructor<AuditEvent> canonicalConstructor() throws NoSuchMethodException {
        Constructor<AuditEvent> constructor = AuditEvent.class.getDeclaredConstructor(
            long.class, String.class, String.class, AuditEvent.EventType.class,
            NodeStatus.class, NodeStatus.class, String.class, String.class, Map.class, Instant.class);
        constructor.setAccessible(true);
        return constructor;
    }

    /**
     * Invokes AuditEvent's package-private canonical constructor via reflection, since
     * this test lives in the same package but wants to exercise the constructor's
     * validation directly rather than only through WorkflowState. Unwraps
     * InvocationTargetException so assertThrows sees the actual validation failure, not
     * the reflection wrapper around it.
     */
    private static void constructViaReflection(long sequence, String runId, String nodeId,
                                                AuditEvent.EventType type, NodeStatus from, NodeStatus to,
                                                String actor, String reason, Map<String, Object> details,
                                                Instant timestamp) {
        try {
            canonicalConstructor().newInstance(
                sequence, runId, nodeId, type, from, to, actor, reason, details, timestamp);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
