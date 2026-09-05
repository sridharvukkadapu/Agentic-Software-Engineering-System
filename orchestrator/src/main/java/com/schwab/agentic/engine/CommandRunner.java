package com.schwab.agentic.engine;

import com.schwab.agentic.model.AuditEvent;
import com.schwab.agentic.model.WorkflowState;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a real shell command with a timeout, captures its exit code and output, and, when
 * given a {@link WorkflowState} and run id, writes the full output to
 * {@code runs/<runId>/commands/<n>-<name>.log} and records an AGENT_CALL-adjacent audit
 * event carrying the command, exit code and duration.
 *
 * Spec 02 introduced a minimal version of this class scoped to what the {@code compiles}
 * and {@code tests-pass} gates needed: a real process exit code, not an assumption of
 * success. Spec 04 extends it in place rather than duplicating it into a second
 * {@code artifact.CommandRunner}, since {@link Gates} already depends on this exact type
 * for those two gates; introducing a second, separately-evolving CommandRunner class
 * would create precisely the kind of divergence CLAUDE.md's zero-tolerance-for-drift
 * stance argues against, for no benefit over extending the one that already exists.
 */
public final class CommandRunner {

    private final AtomicInteger invocationCounter = new AtomicInteger(0);

    /**
     * Runs {@code command} (a shell command line, e.g. "mvn -q -B compile") in
     * {@code workingDirectory}, waiting up to {@code timeout}. Never assumes the command
     * exists: if the shell itself cannot start, that is reported as a distinct failure
     * naming what was expected, not conflated with the command running and failing.
     * Writes nothing to disk and records no audit event; use {@link #runAndRecord} for
     * that.
     */
    public Result run(String command, Path workingDirectory, Duration timeout) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("CommandRunner command must not be blank");
        }
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Could not start command \"" + command + "\" in " + workingDirectory
                    + ". Expected a shell (/bin/sh) to be available.", e);
        }

        long startNanos = System.nanoTime();
        String stdout;
        String stderr;
        try {
            stdout = readAll(process.getInputStream());
            stderr = readAll(process.getErrorStream());
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IllegalStateException("Failed reading output of command \"" + command + "\"", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while waiting for command \"" + command + "\"", e);
        }
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;

        if (!finished) {
            process.destroyForcibly();
            return new Result(command, -1, stdout, stderr, true, durationMillis);
        }

        return new Result(command, process.exitValue(), stdout, stderr, false, durationMillis);
    }

    /** Convenience overload for a bare list of test-time constants, avoids repeating Duration.ofSeconds. */
    public Result run(String command, Path workingDirectory, long timeoutSeconds) {
        return run(command, workingDirectory, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * Runs {@code command} exactly as {@link #run} does, then writes its full stdout and
     * stderr to {@code runs/<runId>/commands/<n>-<name>.log} (n is a per-CommandRunner-
     * instance monotonic counter, so ordering on disk matches invocation order) and
     * records an audit event carrying the command, exit code and duration. This is what
     * satisfies AC-04-10: every invocation through this method has a matching audit event
     * with a real exit code, since the event is built from the same {@link Result} the
     * caller receives, not from a separately-tracked claim.
     */
    public Result runAndRecord(String command, Path workingDirectory, Duration timeout,
                                Path runsDirectory, String runId, String name, WorkflowState state) {
        Result result = run(command, workingDirectory, timeout);

        int n = invocationCounter.incrementAndGet();
        Path commandsDirectory = runsDirectory.resolve(runId).resolve("commands");
        Path logFile = commandsDirectory.resolve(n + "-" + name + ".log");
        String logContent = "$ " + command + "\n\n--- stdout ---\n" + result.stdout()
            + "\n--- stderr ---\n" + result.stderr()
            + "\n--- exit code: " + result.exitCode() + (result.timedOut() ? " (timed out)" : "") + " ---\n";
        try {
            Files.createDirectories(commandsDirectory);
            Files.writeString(logFile, logContent);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write command log " + logFile, e);
        }

        state.record(AuditEvent.EventType.COMMAND_EXECUTED, "system",
            "ran command \"" + command + "\": exit code " + result.exitCode()
                + (result.timedOut() ? " (timed out)" : ""),
            Map.of(
                "command", command,
                "exitCode", (double) result.exitCode(),
                "durationMillis", (double) result.durationMillis(),
                "timedOut", result.timedOut(),
                "logFile", logFile.toString()));

        return result;
    }

    private static String readAll(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * What a command run produced. {@code timedOut} is distinct from a non-zero
     * {@code exitCode}: a gate or caller may want to treat "the command never finished"
     * differently from "the command finished and failed."
     */
    public record Result(String command, int exitCode, String stdout, String stderr, boolean timedOut,
                          long durationMillis) {
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
