package com.jenkinschina.atom.pythonenv;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A Python environment as an atom: create a virtualenv, install what the project needs, then run one command in it.
 *
 * <p>The demo pipelines made the gap obvious: {@code python-build} knows how to produce a wheel, but nothing prepared
 * an interpreter. This is the missing half — and it exports {@code VIRTUAL_ENV} and {@code PATH}, so the following
 * steps see the same interpreter without repeating themselves.
 */
public class PythonEnvAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String interpreter = context.getParam("python", "");
        String venvDir = context.getParam("venv", ".venv");
        String requirements = context.getParam("requirements", "");
        String packages = context.getParam("packages", "");
        String indexUrl = context.getParam("indexUrl", "");
        String command = context.getParam("command", "");
        boolean failOnCommandError = context.getBooleanParam("failOnCommandError", true);
        boolean upgradePip = context.getBooleanParam("upgradePip", false);

        Path venv = workspace.resolve(venvDir).normalize();
        if (!venv.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40001, "the virtualenv must live inside the workspace: " + venvDir);
        }
        Path pythonBin = venv.resolve("bin/python");
        Path pipBin = venv.resolve("bin/pip");
        if (!Files.isExecutable(pythonBin) && Files.isExecutable(venv.resolve("Scripts/python.exe"))) {
            pythonBin = venv.resolve("Scripts/python.exe"); // a Windows agent
            pipBin = venv.resolve("Scripts/pip.exe");
        }

        String base = interpreter.isBlank() ? findPython() : interpreter;
        if (base == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40002,
                    "no python3 on the PATH of this agent; set 'python' to an interpreter");
        }

        if (!Files.isExecutable(pythonBin)) {
            context.log("[python-env] creating " + venvDir + " with " + base);
            Result created = exec(workspace, null, base, "-m", "venv", venvDir);
            if (!created.ok()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40003,
                        "python -m venv failed: " + created.tail());
            }
        }

        if (upgradePip) {
            Result upgraded = exec(workspace, venv, pipBin.toString(), "install", "--upgrade", "pip");
            context.log("[python-env] pip upgrade: " + (upgraded.ok() ? "ok" : upgraded.tail()));
        }

        if (!requirements.isBlank()) {
            Path file = workspace.resolve(requirements).normalize();
            if (!file.startsWith(workspace) || !Files.isRegularFile(file)) {
                return AtomResult.error(ErrorType.USER, 40004, "no requirements file: " + requirements);
            }
            Result installed = exec(workspace, venv, pipBin.toString(), "install",
                    "-r", file.toString());
            if (!installed.ok()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40005,
                        "pip install -r " + requirements + " failed: " + installed.tail());
            }
        }
        for (String name : packages.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> args = new ArrayList<>(List.of(pipBin.toString(), "install", trimmed));
            if (!indexUrl.isBlank()) {
                args.add("--index-url");
                args.add(indexUrl);
            }
            Result installed = exec(workspace, venv, args.toArray(new String[0]));
            if (!installed.ok()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40006,
                        "pip install " + trimmed + " failed: " + installed.tail());
            }
        }

        String version = firstLine(exec(workspace, venv, pythonBin.toString(), "--version").output());
        String pipVersion = firstLine(exec(workspace, venv, pipBin.toString(), "--version").output());
        context.log("[python-env] " + venvDir + ": " + version + " (" + pipVersion + ")");

        int exit = 0;
        if (!command.isBlank()) {
            context.log("[python-env] $ " + command);
            Result ran = exec(workspace, venv, "/bin/sh", "-c", command);
            for (String line : ran.output().split("\n")) {
                if (!line.isEmpty()) {
                    context.log("[python-env] " + line);
                }
            }
            exit = ran.code();
            if (exit != 0 && failOnCommandError) {
                return AtomResult.error(ErrorType.USER, 40007,
                        "the command exited " + exit + ": " + command);
            }
        }

        // the following steps get the same interpreter through the environment
        String path = System.getenv("PATH");
        AtomResult result = AtomResult.success()
                .data("VIRTUAL_ENV", venv.toString())
                .data("PATH", venv.resolve("bin") + (path == null || path.isBlank() ? "" : ":" + path))
                .data("pythonVersion", version)
                .data("pipVersion", pipVersion)
                .data("exitCode", String.valueOf(exit));
        return result;
    }

    private static String findPython() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            for (String name : List.of("python3", "python")) {
                Path candidate = Path.of(entry).resolve(name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    /** Runs with the venv first on PATH and VIRTUAL_ENV set, the way `source activate` would. */
    private static Result exec(Path workspace, Path venv, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            if (venv != null) {
                String path = builder.environment().getOrDefault("PATH", "");
                builder.environment().put("PATH", venv.resolve("bin") + (path.isBlank() ? "" : ":" + path));
                builder.environment().put("VIRTUAL_ENV", venv.toString());
                builder.environment().remove("PYTHONHOME");
            }
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > 200_000) {
                        output.delete(0, 100_000);
                    }
                }
            }
            return new Result(process.waitFor(), output.toString());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.strip().lines().findFirst().orElse("").trim();
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }

        String tail() {
            String[] lines = output.strip().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().strip();
        }
    }
}
