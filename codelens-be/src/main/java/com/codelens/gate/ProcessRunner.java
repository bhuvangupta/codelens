package com.codelens.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external tool with a hard timeout. Arguments are passed as an argv list,
 * never through a shell.
 */
final class ProcessRunner {

    private static final int MAX_OUTPUT_CHARS = 4000;

    private ProcessRunner() {}

    record Result(int exitCode, String output) {}

    static Result run(List<String> command, Path workingDir, Map<String, String> extraEnv, int timeoutSeconds)
            throws IOException, InterruptedException {
        // Output goes to a file so a chatty tool cannot block on a full pipe
        Path outputFile = Files.createTempFile("codelens-proc-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            builder.environment().putAll(extraEnv);

            Process process = builder.start();
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IOException(command.get(0) + " timed out after " + timeoutSeconds + "s");
                }
            } finally {
                // Also covers interruption when the gate cancels a timed-out scan
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(output.length() - MAX_OUTPUT_CHARS);
            }
            return new Result(process.exitValue(), output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort; the OS temp cleaner gets what is left
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
