package dispatch.ui;

import dispatch.core.Groups;
import dispatch.core.TaskService;
import dispatch.store.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * The running bot's desk port (D-2): its task routes for {@code dispatch ui}, on 127.0.0.1, answering only the token it
 * writes into desk.json at every start.
 */
public final class DeskServer implements AutoCloseable {

    private final UiServer server;
    private final Path stateDir;

    private DeskServer(UiServer server, Path stateDir) {
        this.server = server;
        this.stateDir = stateDir;
    }

    public static DeskServer start(Path stateDir, Database db, TaskService tasks, Groups groups, Clock clock, String version,
                                   String name) throws IOException {
        String token = DeskFile.newToken();
        TasksApi tasksApi = new TasksApi(db, tasks, groups, true);
        LiveApi live = new LiveApi(db, tasks, groups, clock, version, name);
        UiServer server = UiServer.start(0, "/desk-serves-no-pages", port -> new DeskAuth(port, token, groups),
                Map.of("/api/live", live::get), tasksApi.routes());
        try {
            new DeskFile(server.port(), token, version, name).write(stateDir);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        return new DeskServer(server, stateDir);
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.close();
        DeskFile.delete(stateDir);
    }
}
