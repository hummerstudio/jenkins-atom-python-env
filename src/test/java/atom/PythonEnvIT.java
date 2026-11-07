package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The python-env atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — a virtualenv is created, the command runs inside it,
 * {@code VIRTUAL_ENV} and {@code PATH} are exported, a missing requirements file and a failing command are refused —
 * written as JUnit.
 */
class PythonEnvIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a virtualenv is created, the command runs inside it, and VIRTUAL_ENV/PATH point at it")
    void createsAVenvAndRunsTheCommandInsideIt() throws Exception {
        AtomResult run = atom.input("venv", ".venv")
                .input("command", "python -c \"import sys;print(sys.prefix)\"")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertTrue(Files.isExecutable(run.work().resolve(".venv/bin/python")), "no virtualenv was created");

        String venv = run.work().resolve(".venv").toString();
        assertEquals(venv, run.field("VIRTUAL_ENV"), "the following steps get the same interpreter");
        assertTrue(run.field("PATH").startsWith(venv + "/bin:"), "PATH must start with the venv bin dir: "
                + run.field("PATH"));
        assertTrue(run.console().contains(venv), "the command did not run inside the virtualenv:\n" + run.console());
    }

    @Test
    @DisplayName("a missing requirements file is a clear user error")
    void refusesAMissingRequirementsFile() throws Exception {
        AtomResult run = atom.input("requirements", "nope.txt").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no requirements file"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a failing command fails the step unless told otherwise")
    void failsOnANonZeroCommand() throws Exception {
        AtomResult run = atom.input("command", "exit 4").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("exited 4"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a virtualenv outside the workspace is refused")
    void refusesAVenvOutsideTheWorkspace() throws Exception {
        AtomResult run = atom.input("venv", "../outside").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("the virtualenv must live inside the workspace"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("no interpreter on PATH is an environment error naming the input that fixes it")
    void refusesWhenThereIsNoPython() throws Exception {
        AtomResult run = atom.input("venv", ".venv").env("PATH", ":/nonexistent").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no python3 on the PATH"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an interpreter that cannot run is reported with the tail of its output")
    void failsWhenTheInterpreterCannotCreateAVenv() throws Exception {
        AtomResult run = atom.input("python", "/nonexistent/python3").input("venv", ".venv").run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("python -m venv failed"), "message: " + output);
        assertTrue(output.contains("No such file"), "the exec failure was swallowed: " + output);
    }

    @Test
    @DisplayName("a Scripts/python.exe virtualenv is used as it is, without being recreated")
    void usesAWindowsLayout() throws Exception {
        Path workspace = Files.createDirectories(Path.of(System.getProperty("atom.scratch"))
                .resolve("win-venv-" + System.nanoTime()));
        Path scripts = Files.createDirectories(workspace.resolve(".venv/Scripts"));
        executable(scripts.resolve("python.exe"), "#!/bin/sh\necho 'Python 3.11.4'\n");
        executable(scripts.resolve("pip.exe"), "#!/bin/sh\necho 'pip 24.0 from site-packages'\n");

        AtomResult run = atom.input("atomWorkspace", workspace.toString()).input("venv", ".venv").run();

        run.assertOk();
        assertEquals("Python 3.11.4", run.field("pythonVersion"));
        assertEquals("pip 24.0 from site-packages", run.field("pipVersion"));
        assertEquals(workspace.resolve(".venv").toString(), run.field("VIRTUAL_ENV"));
    }

    @Test
    @DisplayName("an existing virtualenv gets the pip upgrade, the requirements and the packages")
    void installsIntoAnExistingVenv() throws Exception {
        Path workspace = existingVenv("install");
        Files.writeString(workspace.resolve("requirements.txt"), "requests==2.32.0\n");
        Path argv = workspace.resolve("pip-argv.txt");

        AtomResult run = atom.input("atomWorkspace", workspace.toString())
                .input("venv", ".venv")
                .input("requirements", "requirements.txt")
                .input("packages", "flask, rich")
                .input("indexUrl", "https://mirror.example.com/simple")
                .input("upgradePip", "true")
                .input("command", "python -c \"print('inside')\"")
                .env("PIP_ARGV", argv.toString())
                .run();

        run.assertOk();
        assertEquals("Python 3.11.4", run.field("pythonVersion"));
        assertEquals("pip 24.0", run.field("pipVersion"));
        assertEquals("0", run.field("exitCode"));
        assertTrue(run.console().contains("inside"), "the command did not run:\n" + run.console());
        String commands = Files.readString(argv);
        assertTrue(commands.contains("install --upgrade pip"), "pip was not upgraded:\n" + commands);
        assertTrue(commands.contains("install -r " + workspace.resolve("requirements.txt")),
                "the requirements file was not installed:\n" + commands);
        assertTrue(commands.contains("install flask --index-url https://mirror.example.com/simple"),
                "flask with the index url missing:\n" + commands);
        assertTrue(commands.contains("install rich --index-url https://mirror.example.com/simple"),
                "rich with the index url missing:\n" + commands);
    }

    @Test
    @DisplayName("pip failing on the requirements file is a third-party error with pip's tail")
    void failsOnTheRequirementsInstall() throws Exception {
        Path workspace = existingVenv("requirements-fail");
        Files.writeString(workspace.resolve("requirements.txt"), "not-a-real-package==0.0.0\n");

        AtomResult run = atom.input("atomWorkspace", workspace.toString())
                .input("requirements", "requirements.txt")
                .env("PIP_FAIL", "requirements")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("pip install -r requirements.txt failed"), "message: " + output);
        assertTrue(output.contains("No matching distribution"), "pip's tail was dropped: " + output);
    }

    @Test
    @DisplayName("pip failing on a package names that package, not the whole list")
    void failsOnAPackageInstall() throws Exception {
        Path workspace = existingVenv("package-fail");
        Path argv = workspace.resolve("pip-argv.txt");

        AtomResult run = atom.input("atomWorkspace", workspace.toString())
                .input("packages", "flask, badpkg, rich")
                .env("PIP_ARGV", argv.toString())
                .env("PIP_FAIL", "badpkg")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("pip install badpkg failed"),
                "message: " + run.file("output.json"));
        String commands = Files.readString(argv);
        assertTrue(commands.contains("install flask"), "the first package was skipped:\n" + commands);
        assertTrue(!commands.contains("install rich"), "the list continued after a failure:\n" + commands);
    }

    /** A workspace with a ready-made virtualenv: python and pip are shell scripts the test can steer. */
    private static Path existingVenv(String name) throws Exception {
        Path workspace = Files.createDirectories(Path.of(System.getProperty("atom.scratch"))
                .resolve("venv-" + name + "-" + System.nanoTime()));
        Path bin = Files.createDirectories(workspace.resolve(".venv/bin"));
        executable(bin.resolve("python"), "#!/bin/sh\ncase \"$1\" in --version) echo 'Python 3.11.4' ;;"
                + " *) echo \"ran $*\" ;; esac\n");
        executable(bin.resolve("pip"), "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$PIP_ARGV\"\n"
                + "fail=\"${PIP_FAIL:-__none__}\"\n"
                + "case \"$*\" in\n"
                + "  *\"$fail\"*) echo 'ERROR: No matching distribution found'; exit 1 ;;\n"
                + "  *--version*) echo 'pip 24.0' ;;\n"
                + "esac\nexit 0\n");
        return workspace;
    }

    private static void executable(Path file, String script) throws Exception {
        Files.writeString(file, script);
        file.toFile().setExecutable(true);
    }
}
