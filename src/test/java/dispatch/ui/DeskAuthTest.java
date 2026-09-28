package dispatch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import dispatch.config.Config;
import dispatch.core.Groups;
import dispatch.ui.UiServer.Caller;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The desk port's token, and who the owner acts as (D-2). */
class DeskAuthTest {

    private static final String TOKEN = DeskFile.newToken();

    private final Groups team = new Groups(new Config.Telegram(List.of(100L, 300L), List.of(new Config.Group("backend", -100L,
            List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali"), new Config.Member(300, "Saraa")), List.of("alm")))));
    private final Groups personal = new Groups(new Config.Telegram(List.of(), List.of(new Config.Group("bold", null,
            List.of(new Config.Member(100, "Bold")), List.of("alm")))));

    @Test
    void noTokenOrAWrongOneIsRefused() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);

        assertEquals("desk_token", assertThrows(ApiException.class, () -> auth.caller(exchange(Map.of()))).code());
        assertEquals("desk_token", assertThrows(ApiException.class,
                () -> auth.caller(exchange(Map.of("Authorization", "desk " + "0".repeat(64))))).code());
    }

    @Test
    void aTeamWithSeveralAdminsIsAskedWhichOneThisIs() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);

        assertEquals("choose_member", assertThrows(ApiException.class,
                () -> auth.caller(exchange(Map.of("Authorization", "desk " + TOKEN)))).code());
        Caller chosen = auth.caller(exchange(Map.of("Authorization", "desk " + TOKEN, DeskAuth.MEMBER_HEADER, "telegram:300")));

        assertEquals(new Caller("telegram:300", "Saraa", true), chosen);
    }

    @Test
    void aMemberWhoIsNoAdminMayNotBeChosen() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);

        assertEquals("not_owner", assertThrows(ApiException.class, () -> auth.caller(
                exchange(Map.of("Authorization", "desk " + TOKEN, DeskAuth.MEMBER_HEADER, "telegram:200")))).code());
    }

    @Test
    void aPersonalBotIsItsOneMember() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, personal);

        assertEquals("telegram:100", auth.caller(exchange(Map.of("Authorization", "desk " + TOKEN))).ref());
    }

    @Test
    void anotherHostIsRefusedAndNoPageIsServed() {
        DeskAuth auth = new DeskAuth(4000, TOKEN, team);

        assertEquals(Optional.empty(), auth.hostRefusal("127.0.0.1:4000"));
        assertTrue(auth.hostRefusal("localhost:4000").isPresent());
        assertTrue(auth.hostRefusal("evil.example:4000").isPresent());
        assertTrue(auth.pageRefusal(exchange(Map.of())).isPresent());
    }

    /** A request with only these headers; nothing else of it is read. */
    private static HttpExchange exchange(Map<String, String> headers) {
        Headers request = new Headers();
        headers.forEach(request::add);
        return new HttpExchange() {
            @Override
            public Headers getRequestHeaders() {
                return request;
            }

            @Override
            public Headers getResponseHeaders() {
                throw new UnsupportedOperationException();
            }

            @Override
            public URI getRequestURI() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getRequestMethod() {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpContext getHttpContext() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getRequestBody() {
                throw new UnsupportedOperationException();
            }

            @Override
            public OutputStream getResponseBody() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void sendResponseHeaders(int status, long length) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InetSocketAddress getRemoteAddress() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getResponseCode() {
                throw new UnsupportedOperationException();
            }

            @Override
            public InetSocketAddress getLocalAddress() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getProtocol() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object getAttribute(String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setAttribute(String name, Object value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setStreams(InputStream in, OutputStream out) {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpPrincipal getPrincipal() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
