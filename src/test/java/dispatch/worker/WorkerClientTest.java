package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** How {@link WorkerClient} builds its request URLs: a reverse proxy's own sub-path must survive. */
class WorkerClientTest {

    @Test
    void aTeamUrlWithASubPathIsKeptNotStrippedToTheBareHost() throws Exception {
        // Stands in for a reverse proxy that publishes Dispatch under a sub-path (workers.publicUrl =
        // https://host/dispatch): only this context answers, so URI.resolve's own bug (it drops "/dispatch" and asks
        // the bare host for /api/worker/projects instead) would 404 here instead of reaching Setup.
        HttpServer proxyLike = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        proxyLike.createContext("/dispatch" + WorkerApi.PROJECTS, exchange -> {
            byte[] body = ("{\"team\":\"backend\",\"authorName\":\"Dispatch (backend)\","
                    + "\"authorEmail\":\"dispatch-backend@example.com\",\"projects\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        proxyLike.start();
        try {
            URI team = URI.create("http://127.0.0.1:" + proxyLike.getAddress().getPort() + "/dispatch");
            WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), team, "some-key");

            WorkerClient.Setup setup = client.setup();

            assertEquals("backend", setup.team());
            assertEquals("Dispatch (backend)", setup.authorName());
        } finally {
            proxyLike.stop(0);
        }
    }

    @Test
    void aTrailingSlashOnTheTeamUrlIsHarmless() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext(WorkerApi.PROJECTS, exchange -> {
            byte[] body = ("{\"team\":\"backend\",\"authorName\":\"Dispatch (backend)\","
                    + "\"authorEmail\":\"dispatch-backend@example.com\",\"projects\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            URI team = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            WorkerClient client = new WorkerClient(HttpClient.newHttpClient(), team, "some-key");

            WorkerClient.Setup setup = client.setup();

            assertEquals("backend", setup.team());
        } finally {
            server.stop(0);
        }
    }
}
