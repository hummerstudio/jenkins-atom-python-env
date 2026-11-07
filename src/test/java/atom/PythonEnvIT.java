package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
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
}
