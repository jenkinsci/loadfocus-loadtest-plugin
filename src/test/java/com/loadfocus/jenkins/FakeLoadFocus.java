package com.loadfocus.jenkins;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process stand-in for the LoadFocus public API, shaped after lfnew's loadtestingpublic.js responses. */
final class FakeLoadFocus implements AutoCloseable {
    static final String KEY = "lfkey-0123456789abcdef";

    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();

    volatile String runId = "7";
    volatile boolean executeCreatesRun = true;
    /** Run ids the execute call consumes; 2 simulates another build launching the same test concurrently. */
    volatile int executeIncrement = 1;
    /** Next N /state calls answer the HTML maintenance page with 503. */
    volatile int stateFailures = 0;
    volatile int executeStatus = 200;
    volatile String executeBody = "{\"success\":\"true\",\"message\":\"Execution started\"}";
    final Deque<String> states = new ArrayDeque<>(List.of("initializing", "running", "finished"));
    volatile String analysis = "{\"samples\":10,\"labels\":[{\"label\":\"https://example.com/\",\"mean\":120.5,\"errorPct\":0}]}";
    volatile String verdict = "{\"verdict\":\"pass\",\"enabled\":true,\"metricsAvailable\":true,"
            + "\"checks\":[{\"key\":\"p95Ms\",\"label\":\"P95 response time\",\"unit\":\"ms\",\"dir\":\"max\",\"target\":500,\"actual\":310,\"pass\":true}],"
            + "\"metrics\":{\"source\":\"analysis\",\"p95Ms\":310,\"p99Ms\":420,\"errorRatePct\":0,\"rps\":212.9,\"samples\":14895},"
            + "\"unevaluated\":[],\"cwv\":{\"verdict\":\"none\",\"enabled\":false,\"checks\":[],\"unevaluated\":[]}}";
    volatile int validateStatus = 200;
    volatile int annotateStatus = 200;
    volatile int thresholdsStatus = 200;
    final List<String> bodies = new CopyOnWriteArrayList<>();

    private final HttpServer server;

    FakeLoadFocus() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            bodies.add(path + " " + body);
        }
        String auth = ex.getRequestHeaders().getFirst("loadfocus-auth");
        authHeaders.add(auth == null ? "" : auth);
        if (!KEY.equals(auth)) {
            reply(ex, 403, "{\"response\":\"invalid\"}");
            return;
        }
        switch (path) {
            case "/api/v1/key/validate":
                reply(ex, validateStatus, validateStatus == 200 ? "{\"response\":\"valid\"}" : "{\"response\":\"invalid\"}");
                return;
            case "/api/v1/loadtests":
                reply(ex, 200, "[{\"testrunname\":\"checkout\",\"testrunid\":\"" + runId + "\"}]");
                return;
            case "/api/v1/loadtests/retrieveconfig":
                reply(ex, 200, "{\"testrunname\":\"checkout\",\"testrunid\":\"" + runId + "\"}");
                return;
            case "/api/v1/loadtests/newtest/execute":
                if (executeStatus == 200 && executeCreatesRun) {
                    runId = String.valueOf(Integer.parseInt(runId) + executeIncrement);
                }
                reply(ex, executeStatus, executeBody);
                return;
            case "/api/v1/loadtests/state": {
                if (stateFailures > 0) {
                    stateFailures--;
                    replyHtml(ex, 503, "<html><body>Down for maintenance</body></html>");
                    return;
                }
                String s;
                synchronized (states) {
                    s = states.size() > 1 ? states.poll() : states.peek();
                }
                reply(ex, 200, s.isEmpty() ? "{}" : "{\"testrunname\":\"checkout\",\"testrunid\":\"" + runId + "\",\"state\":\"" + s + "\"}");
                return;
            }
            case "/api/v1/loadtests/analysis":
                reply(ex, 200, analysis);
                return;
            case "/api/v1/loadtests/verdict":
                reply(ex, 200, verdict);
                return;
            case "/api/v1/loadtests/thresholds":
                reply(ex, thresholdsStatus, thresholdsStatus == 200 ? "{\"enabled\":true}" : "{\"error\":\"invalid-errorRatePct\"}");
                return;
            case "/api/test/general/run-annotation":
                reply(ex, annotateStatus, annotateStatus == 200 ? "{\"ok\":true}" : "{\"error\":\"invalid-fields\"}");
                return;
            case "/api/v1/loadtests/share":
                reply(ex, 200, "{\"url\":\"https://loadfocus.com/share/tok123\"}");
                return;
            default:
                reply(ex, 404, "{\"error\":\"not-found\"}");
        }
    }

    String body(String path) {
        return bodies.stream().filter(b -> b.startsWith(path + " ")).map(b -> b.substring(path.length() + 1)).findFirst().orElse(null);
    }

    int indexOf(String fragment) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    boolean called(String pathFragment) {
        return requests.stream().anyMatch(r -> r.contains(pathFragment));
    }

    private static void replyHtml(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
