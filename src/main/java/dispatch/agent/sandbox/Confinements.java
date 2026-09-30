package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The one place a process turns its sandbox and its own files into the Confinement its agents share. */
public final class Confinements {

    private Confinements() {
    }

    /**
     * @param configFile    the instance's or worker's config; its directory holds every instance's secrets file
     * @param projectClones the clones configured here, each hidden from runs of the others
     * @param otherInstancesPrivate sibling instances' state dirs and clones, so one instance's agent cannot read another's
     */
    public static Confinement of(Sandbox sandbox, Path stateDir, Path configFile, List<Path> projectClones,
                                 List<Path> otherInstancesPrivate) {
        List<Path> dispatchPrivate = new ArrayList<>();
        dispatchPrivate.add(configFile.toAbsolutePath().getParent());
        dispatchPrivate.add(stateDir.toAbsolutePath());
        projectClones.forEach(clone -> dispatchPrivate.add(clone.toAbsolutePath()));
        dispatchPrivate.addAll(otherInstancesPrivate);
        return new Confinement(sandbox, new SandboxPolicies(Path.of(System.getProperty("user.home")), stateDir.toAbsolutePath(), dispatchPrivate,
                projectClones.stream().map(Path::toAbsolutePath).toList()));
    }
}
