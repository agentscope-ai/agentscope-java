/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.harness.sandbox;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class DockerPythonSandboxImage {

    static final String IMAGE = "agentscope/python-sandbox:py311-slim";

    private DockerPythonSandboxImage() {}

    static void ensureImage() throws Exception {
        Path dockerfile = findDockerfile();
        CommandResult dockerInfo = runAllowFailure(dockerInfoCommand(), 30);
        if (dockerInfo.exitCode() != 0) {
            throw new IllegalStateException(dockerAvailabilityErrorMessage(dockerInfo.output()));
        }
        if (runAllowFailure(inspectCommand(IMAGE), 30).exitCode() == 0) {
            System.out.println("✓ Docker image exists: " + IMAGE);
            return;
        }
        System.out.println("Docker image not found. Building: " + IMAGE);
        runStreaming(
                buildCommand(IMAGE, dockerfile),
                1800,
                "Failed to build Docker image: " + IMAGE,
                System.out,
                System.err);
    }

    static List<String> dockerInfoCommand() {
        return List.of("docker", "info");
    }

    static List<String> inspectCommand(String image) {
        return List.of("docker", "image", "inspect", image);
    }

    static List<String> buildCommand(String image, Path dockerfile) {
        return List.of(
                "docker",
                "build",
                "-t",
                image,
                "-f",
                dockerfile.toString(),
                dockerfile.getParent().toString());
    }

    static String dockerAvailabilityErrorMessage(String output) {
        String details = output != null ? output.strip() : "";
        if (details.contains("Cannot connect to the Docker daemon")) {
            return "Docker CLI is available, but the Docker daemon is not reachable."
                    + " Please start Docker Desktop or verify the active Docker context/socket.\n"
                    + details;
        }
        return "Docker is not available or not usable. Please install Docker and ensure the"
                + " Docker daemon is running.\n"
                + details;
    }

    private static Path findDockerfile() {
        String relative = "src/main/docker/python-sandbox/Dockerfile";
        String moduleRelative =
                "agentscope-examples/harness-examples/harness-sandbox-docker/" + relative;
        Path cwd = Path.of(System.getProperty("user.dir"));
        for (Path candidate : List.of(cwd.resolve(relative), cwd.resolve(moduleRelative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
                "Cannot find Dockerfile. Run from repository root or harness-sandbox module.");
    }

    private static void run(List<String> command, int timeoutSeconds, String errorMessage)
            throws Exception {
        CommandResult result = runAllowFailure(command, timeoutSeconds);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(errorMessage + "\n" + result.output());
        }
    }

    static void runStreaming(
            List<String> command,
            int timeoutSeconds,
            String errorMessage,
            PrintStream stdout,
            PrintStream stderr)
            throws Exception {
        Process process = new ProcessBuilder(command).start();
        Thread stdoutForwarder = pipe(process.getInputStream(), stdout, "docker-build-stdout");
        Thread stderrForwarder = pipe(process.getErrorStream(), stderr, "docker-build-stderr");
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutForwarder.join();
            stderrForwarder.join();
            stderr.println("Command timed out: " + String.join(" ", command));
            throw new IllegalStateException(errorMessage);
        }
        stdoutForwarder.join();
        stderrForwarder.join();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(errorMessage);
        }
    }

    private static Thread pipe(java.io.InputStream source, PrintStream target, String name) {
        Thread thread =
                new Thread(
                        () -> {
                            try (source) {
                                source.transferTo(target);
                                target.flush();
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        },
                        name);
        thread.start();
        return thread;
    }

    private static CommandResult runAllowFailure(List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(124, "Command timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private record CommandResult(int exitCode, String output) {}
}
