package com.loadfocus.jenkins.api;

import hudson.ProxyConfiguration;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the LoadFocus public load-test API (cloud "general" tests).
 * The API key travels only in the {@code loadfocus-auth} header and is never logged.
 */
public class LoadAPI {
    public static final String DEFAULT_BASE_URL = "https://loadfocus.com/";
    private static final String TESTS = "api/v1/loadtests";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public LoadAPI(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey);
    }

    public LoadAPI(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.http = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // Never follow redirects: the API key header would travel to the redirect target.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Result of an HTTP call: status code plus raw body. */
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        JSON json() throws LoadAPIException {
            try {
                return JSONSerializer.toJSON(body);
            } catch (JSONException e) {
                throw new LoadAPIException("unexpected non-JSON response (HTTP " + status + ")", status);
            }
        }

        JSONObject object() throws LoadAPIException {
            JSON j = json();
            if (!(j instanceof JSONObject)) {
                throw new LoadAPIException("unexpected response shape (HTTP " + status + ")", status);
            }
            return (JSONObject) j;
        }
    }

    /** Outcome of launching a run. */
    public static final class ExecuteResult {
        public final boolean started;
        public final String error;

        ExecuteResult(boolean started, String error) {
            this.started = started;
            this.error = error;
        }
    }

    public boolean isValidApiKey() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        try {
            Response r = get("api/v1/key/validate");
            return r.status == 200 && "valid".equals(r.object().optString("response"));
        } catch (IOException | LoadAPIException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The account's cloud tests as {testrunname, testrunid} maps, or null when the key is rejected. */
    public List<Map<String, String>> getTestList() throws IOException, InterruptedException, LoadAPIException {
        Response r = get(TESTS);
        if (r.status != 200) {
            return null;
        }
        JSON j = r.json();
        if (!(j instanceof JSONArray)) {
            return null;
        }
        List<Map<String, String>> tests = new ArrayList<>();
        for (Object o : (JSONArray) j) {
            JSONObject t = (JSONObject) o;
            Map<String, String> m = new HashMap<>();
            m.put("testrunname", t.optString("testrunname"));
            m.put("testrunid", t.optString("testrunid"));
            tests.add(m);
        }
        return tests;
    }

    /** The test's latest run id ("" when the test has never run). */
    public String latestRunId(String testrunname) throws IOException, InterruptedException, LoadAPIException {
        Response r = get(TESTS + "/retrieveconfig?testrunname=" + enc(testrunname));
        requireOk(r, "read test configuration");
        return r.object().optString("testrunid", "");
    }

    public ExecuteResult execute(String testrunname) throws IOException, InterruptedException {
        Response r = post(TESTS + "/newtest/execute?testrunname=" + enc(testrunname));
        JSONObject body = null;
        try {
            body = r.object();
        } catch (LoadAPIException e) {
            // fall through: reported below as an HTTP error
        }
        if (r.status == 200 && body != null && "true".equals(body.optString("success"))) {
            return new ExecuteResult(true, null);
        }
        String error = body == null ? null : body.optString("error", null);
        if (error == null && body != null) {
            error = body.optString("response", null);
        }
        return new ExecuteResult(false, error != null ? error : "HTTP " + r.status);
    }

    public JSONObject getState(String testrunname, String testrunid) throws IOException, InterruptedException, LoadAPIException {
        Response r = get(TESTS + "/state?testrunname=" + enc(testrunname) + "&testrunid=" + enc(testrunid));
        requireOk(r, "read run state");
        return r.object();
    }

    /** Whole-run analysis (overall + per-label metrics), or null while the run has no samples yet. */
    public JSONObject getAnalysis(String testrunname, String testrunid) throws IOException, InterruptedException, LoadAPIException {
        Response r = get(TESTS + "/analysis?testrunname=" + enc(testrunname) + "&testrunid=" + enc(testrunid));
        if (r.status == 404) {
            return null;
        }
        requireOk(r, "read run analysis");
        return r.object();
    }

    /** Server-side pass/fail verdict against the thresholds configured on loadfocus.com. */
    public JSONObject getVerdict(String testrunname, String testrunid) throws IOException, InterruptedException, LoadAPIException {
        Response r = get(TESTS + "/verdict?testrunname=" + enc(testrunname) + "&testrunid=" + enc(testrunid));
        requireOk(r, "read verdict");
        return r.object();
    }

    /** Creates (or returns) a public share link for a finished run. */
    public String createShareLink(String testrunname, String testrunid) throws IOException, InterruptedException, LoadAPIException {
        JSONObject payload = new JSONObject();
        payload.put("testrunname", testrunname);
        payload.put("testrunid", testrunid);
        HttpRequest req = request(TESTS + "/share")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        Response r = send(req);
        requireOk(r, "create share link");
        return r.object().optString("url", null);
    }

    /**
     * Replaces the test's pass/fail thresholds on loadfocus.com (metrics left out are cleared) and enables them.
     * Values: p95Ms, p99Ms, errorRatePct, minRps.
     */
    public void putThresholds(String testrunname, Map<String, Number> values) throws IOException, InterruptedException, LoadAPIException {
        JSONObject payload = new JSONObject();
        payload.put("testrunname", testrunname);
        payload.put("enabled", true);
        for (Map.Entry<String, Number> e : values.entrySet()) {
            payload.put(e.getKey(), e.getValue());
        }
        HttpRequest req = request(TESTS + "/thresholds")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        requireOk(send(req), "save thresholds");
    }

    /** Labels a run (shown on its LoadFocus results and trend), e.g. with the Jenkins build that started it. */
    public void annotateRun(String testrunname, String testrunid, String label, String url) throws IOException, InterruptedException, LoadAPIException {
        String form = "testrunname=" + enc(testrunname) + "&testrunid=" + enc(testrunid) + "&label=" + enc(label)
                + (url == null ? "" : "&url=" + enc(url));
        HttpRequest req = request("api/test/general/run-annotation")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        requireOk(send(req), "tag run");
    }

    public String resultsUrl(String testrunname, String testrunid) {
        return resultsUrl(baseUrl, testrunname, testrunid);
    }

    public static String resultsUrl(String baseUrl, String testrunname, String testrunid) {
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                + "tests?testrunname=" + enc(testrunname) + "&testrunid=" + enc(testrunid);
    }

    private static void requireOk(Response r, String what) throws LoadAPIException {
        if (r.status != 200) {
            String detail = "";
            try {
                JSONObject o = r.object();
                detail = o.optString("error", o.optString("response", ""));
            } catch (LoadAPIException ignored) {
                // no JSON body
            }
            throw new LoadAPIException("could not " + what + ": HTTP " + r.status + (detail.isEmpty() ? "" : " (" + detail + ")"), r.status);
        }
    }

    private Response get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build());
    }

    private Response post(String path) throws IOException, InterruptedException {
        return send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json")
                .header("loadfocus-auth", apiKey == null ? "" : apiKey);
    }

    private Response send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(resp.statusCode(), resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
