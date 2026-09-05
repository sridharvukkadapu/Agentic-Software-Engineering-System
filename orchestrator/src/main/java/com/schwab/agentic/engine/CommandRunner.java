package com.schwab.agentic.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs a real shell command with a timeout and reports its exit code and output.
 *
 * This is a minimal precursor to spec 04's fuller CommandRunner (which also writes
 * output to `runs/&lt;runId&gt;/commands/` and emits an audit event per invocation). This
 * spec needs a real command execution path for the {@code compiles} and
 * {@code tests-pass} exit gates: those gates must observe an actual process exit code,
 * per CLAUDE.md rule 2, not assume success because an executor claimed one.
 */
public final class CommandRunner {

    /**
     * Runs {@code command} (a shell command line, e.g. "mvn -q -B compile") in
     * {@code workingDirectory}, waiting up to {@code timeout}. Never assumes the command
     * exists: if the shell itself cannot start, that is reported as a distinct failure
     * naming what was expected, not conflated with the command running and failing.
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

        if (!finished) {
            process.destroyForcibly();
            return new Result(command, -1, stdout, stderr, true);
        }

        return new Result(command, process.exitValue(), stdout, stderr, false);
    }

    private static String readAll(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * What a command run produced. {@code timedOut} is distinct from a non-zero
     * {@code exitCode}: a gate or caller may want to treat "the command never finished"
     * differently from "the command finished and failed."
     */
    public record Result(String command, int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    /** Convenience overload for a bare list of test-time constants, avoids repeating Duration.ofSeconds. */
    public Result run(String command, Path workingDirectory, long timeoutSeconds) {
        return run(command, workingDirectory, Duration.ofSeconds(timeoutSeconds));
    }
}
