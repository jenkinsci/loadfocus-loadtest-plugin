package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.loadfocus.jenkins.api.LoadAPI;
import com.loadfocus.jenkins.api.LoadAPIException;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a LoadFocus cloud load test and marks the build from its results.
 * Freestyle: post-build action. Pipeline: {@link LoadFocusStep} ({@code loadfocusLoadTest}).
 */
public class LoadPublisher extends Notifier implements SimpleBuildStep {
    static final int DEFAULT_TIMEOUT_MINUTES = 120;
    /** Run states that end a run; anything else is still starting or running. */
    static final Set<String> FAILED_STATES = new HashSet<>(Arrays.asList(
            "aborted", "error", "finished_failed_to_run", "finished_failed_timeout", "stopped"));
    static final String FINISHED = "finished";

    // Overridable by tests only.
    @Restricted(NoExternalUse.class) static String baseUrl = LoadAPI.DEFAULT_BASE_URL;
    @Restricted(NoExternalUse.class) static long pollMillis = 5_000;
    @Restricted(NoExternalUse.class) static long minuteMillis = 60_000;
    @Restricted(NoExternalUse.class) static int resultAttempts = 18;
    /** Consecutive transient API failures (5xx, 429, network) tolerated before giving up: ~5 min at 5 s. */
    @Restricted(NoExternalUse.class) static int maxTransientFailures = 60;

    private String apiKey;
    private String testId = "";
    private String testName;
    // -1 = threshold not used. Builds saved by 1.1.x always stored explicit values (0 = any error fails).
    private int errorFailedThreshold = -1;
    private int errorUnstableThreshold = -1;
    private int responseTimeFailedThreshold = -1;
    private int responseTimeUnstableThreshold = -1;
    private boolean useVerdict;
    private int timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
    private boolean shareReport;
    // null on jobs saved before 1.2 and on new jobs alike: tagging is on unless explicitly disabled.
    private Boolean tagRun;
    private String releaseTag;
    // Thresholds saved to the test on loadfocus.com before the run (null = not managed from Jenkins).
    private Integer p95Ms;
    private Integer p99Ms;
    private Double errorRatePct;
    private Double minRps;

    /** What a run produced; returned to Pipeline scripts by {@link LoadFocusStep}. */
    static final class Outcome {
        String testrunname;
        String testrunid;
        Result result = Result.SUCCESS;
        String verdict;
        String reportUrl;
        final Map<String, Object> metrics = new LinkedHashMap<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("testrunname", testrunname);
            m.put("testrunid", testrunid);
            m.put("result", result.toString());
            m.put("verdict", verdict);
            m.put("reportUrl", reportUrl);
            m.put("metrics", new LinkedHashMap<>(metrics));
            return m;
        }
    }

    @DataBoundConstructor
    public LoadPublisher(String testId) {
        this.testId = Util.fixNull(testId).trim();
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull EnvVars env, @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        Outcome o = runTest(run, env, listener);
        if (o.result.isWorseThan(Result.SUCCESS)) {
            info(listener.getLogger(), "Marking build " + o.result);
            run.setResult(o.result);
        }
    }

    /**
     * Launches the test, waits for it and evaluates it. Throws AbortException on any FAILURE, after
     * recording the results link, so a Pipeline stops at this gate and catchError can handle it.
     */
    Outcome runTest(Run<?, ?> run, EnvVars env, TaskListener listener) throws InterruptedException, IOException {
        PrintStream log = listener.getLogger();
        String testrunname = env.expand(testId);
        if (testrunname.isEmpty() || "-1".equals(testrunname)) {
            throw new AbortException(prefix("no LoadFocus test selected (testId)"));
        }
        validateThresholds();

        String key = resolveApiKey(run);
        if (key == null) {
            // The credential id embeds part of the key, so it is not printed.
            throw new AbortException(prefix(Util.fixEmpty(apiKey) != null
                    ? "the selected LoadFocus API key credential was not found or is not available to this job"
                    : "no LoadFocus API key credential selected and no default key configured. Add one under Manage Jenkins > Credentials"));
        }
        LoadAPI api = new LoadAPI(baseUrl, key);

        String testrunid = null;
        String resultsUrl = null;
        Outcome out = new Outcome();
        out.testrunname = testrunname;
        try {
            info(log, "Test: " + testrunname);
            logConfig(log);

            Map<String, Number> server = serverThresholds();
            if (!server.isEmpty()) {
                api.putThresholds(testrunname, server);
                info(log, "Thresholds saved to the test on LoadFocus: " + server);
            }

            String previousRunId = api.latestRunId(testrunname);
            LoadAPI.ExecuteResult exec = api.execute(testrunname);
            if (!exec.started) {
                throw new AbortException(prefix("test did not start: " + describeError(exec.error)));
            }
            testrunid = api.latestRunId(testrunname);
            if (!isNextRun(previousRunId, testrunid)) {
                throw new AbortException(prefix(testrunid.equals(previousRunId)
                        ? "test was accepted but no new run appeared (latest run is still #" + previousRunId + "); not evaluating an older run"
                        : "another run of this test started at the same time (expected run #" + nextRunId(previousRunId)
                                + ", latest is #" + testrunid + "); cannot tell which run belongs to this build"));
            }
            out.testrunid = testrunid;
            resultsUrl = api.resultsUrl(testrunname, testrunid);
            info(log, "Run #" + testrunid + " started: " + resultsUrl);
            if (isTagRun()) {
                tag(api, log, run, env, testrunname, testrunid);
            }

            waitForRun(api, log, testrunname, testrunid);

            Result result = Result.SUCCESS;
            String verdict = null;
            if (hasLocalThresholds()) {
                result = result.combine(checkLocalThresholds(api, log, testrunname, testrunid, out));
            }
            if (isVerdictUsed()) {
                JSONObject v = fetchVerdict(api, log, testrunname, testrunid);
                verdict = v.optString("verdict", "none");
                result = result.combine(checkVerdict(log, v));
                JSONObject m = v.optJSONObject("metrics");
                if (m != null && !m.isNullObject()) {
                    putMetric(out, "p95Ms", m.opt("p95Ms"));
                    putMetric(out, "p99Ms", m.opt("p99Ms"));
                    putMetric(out, "errorRatePct", m.opt("errorRatePct"));
                    putMetric(out, "rps", m.opt("rps"));
                    putMetric(out, "samples", m.opt("samples"));
                }
            }

            String reportUrl = resultsUrl;
            if (shareReport) {
                try {
                    String shared = api.createShareLink(testrunname, testrunid);
                    if (shared != null) {
                        reportUrl = shared;
                        info(log, "Share link: " + shared);
                    }
                } catch (LoadAPIException e) {
                    info(log, "Share link not created: " + e.getMessage());
                }
            }

            run.addAction(new LoadBuildAction(testrunname, testrunid, reportUrl, verdict));
            info(log, "Results: " + resultsUrl);
            if (result.isWorseThan(Result.UNSTABLE)) {
                // Throw so a Pipeline stops here (a gate) and catchError can handle it.
                throw new AbortException(prefix("load test failed its thresholds; marking build " + result));
            }
            out.result = result;
            out.verdict = verdict;
            out.reportUrl = reportUrl;
            return out;
        } catch (LoadAPIException e) {
            throw new AbortException(prefix(e.getMessage()));
        } catch (InterruptedException e) {
            if (testrunid != null) {
                info(log, "Build aborted. LoadFocus run #" + testrunid + " keeps running until it finishes: " + resultsUrl);
            }
            throw e;
        }
    }

    private void tag(LoadAPI api, PrintStream log, Run<?, ?> run, EnvVars env, String testrunname, String testrunid)
            throws InterruptedException {
        String label = Util.fixEmptyAndTrim(env.expand(Util.fixNull(releaseTag)));
        if (label == null) {
            label = "Jenkins " + run.getFullDisplayName();
        }
        String root = Jenkins.get().getRootUrl();
        String url = root == null ? null : root + run.getUrl();
        try {
            api.annotateRun(testrunname, testrunid, label, url);
            info(log, "Run #" + testrunid + " tagged \"" + label + "\"");
        } catch (IOException | LoadAPIException e) {
            info(log, "Run not tagged (" + e.getMessage() + "); continuing");
        }
    }

    private static void putMetric(Outcome out, String key, Object value) {
        if (value instanceof Number) {
            out.metrics.put(key, value);
        }
    }

    Map<String, Number> serverThresholds() {
        Map<String, Number> m = new LinkedHashMap<>();
        if (p95Ms != null) {
            m.put("p95Ms", p95Ms);
        }
        if (p99Ms != null) {
            m.put("p99Ms", p99Ms);
        }
        if (errorRatePct != null) {
            m.put("errorRatePct", errorRatePct);
        }
        if (minRps != null) {
            m.put("minRps", minRps);
        }
        return m;
    }

    /** Thresholds managed from Jenkins are only useful if the verdict is checked. */
    private boolean isVerdictUsed() {
        return useVerdict || !serverThresholds().isEmpty();
    }

    static boolean isNextRun(String previousRunId, String testrunid) {
        try {
            return Long.parseLong(testrunid) == Long.parseLong(nextRunId(previousRunId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String nextRunId(String previousRunId) {
        try {
            return String.valueOf((previousRunId == null || previousRunId.isEmpty() ? 0 : Long.parseLong(previousRunId)) + 1);
        } catch (NumberFormatException e) {
            return "?";
        }
    }

    interface ApiCall<T> {
        T call() throws IOException, InterruptedException, LoadAPIException;
    }

    /** Retries network errors, 5xx (e.g. the maintenance page during a LoadFocus deploy) and 429 until a cap or deadline. */
    private static <T> T withRetry(PrintStream log, long deadline, ApiCall<T> call)
            throws IOException, InterruptedException, LoadAPIException {
        int failures = 0;
        while (true) {
            String reason;
            try {
                return call.call();
            } catch (LoadAPIException e) {
                if (!e.isTransient()) {
                    throw e;
                }
                reason = e.getMessage();
                if (++failures >= maxTransientFailures || System.currentTimeMillis() > deadline) {
                    throw e;
                }
            } catch (IOException e) {
                reason = "network error: " + e.getMessage();
                if (++failures >= maxTransientFailures || System.currentTimeMillis() > deadline) {
                    throw new AbortException(prefix("LoadFocus API unreachable: " + e.getMessage()));
                }
            }
            if (failures == 1) {
                info(log, "LoadFocus API temporarily unavailable (" + reason + "), retrying");
            }
            Thread.sleep(pollMillis);
        }
    }

    private void waitForRun(LoadAPI api, PrintStream log, String testrunname, String testrunid)
            throws IOException, InterruptedException, LoadAPIException {
        int timeout = timeoutMinutes > 0 ? timeoutMinutes : DEFAULT_TIMEOUT_MINUTES;
        long deadline = System.currentTimeMillis() + timeout * minuteMillis;
        String lastState = null;
        long started = System.currentTimeMillis();
        int emptyStates = 0;
        while (true) {
            JSONObject state = withRetry(log, deadline, () -> api.getState(testrunname, testrunid));
            String current = state.optString("state", "");
            emptyStates = current.isEmpty() ? emptyStates + 1 : 0;
            if (emptyStates >= maxTransientFailures) {
                throw new AbortException(prefix("LoadFocus reports no state for run #" + testrunid + "; giving up"));
            }
            if (FINISHED.equalsIgnoreCase(current)) {
                info(log, "Run #" + testrunid + " finished");
                return;
            }
            if (FAILED_STATES.contains(current.toLowerCase())) {
                throw new AbortException(prefix("run #" + testrunid + " ended with state '" + current + "'"));
            }
            if (!current.equals(lastState)) {
                long sec = (System.currentTimeMillis() - started) / 1000;
                info(log, "Run state: " + (current.isEmpty() ? "waiting" : current) + " (" + sec + "s)");
                lastState = current;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AbortException(prefix("run #" + testrunid + " did not finish within " + timeout + " minutes (last state '" + current + "')"));
            }
            Thread.sleep(pollMillis);
        }
    }

    private Result checkLocalThresholds(LoadAPI api, PrintStream log, String testrunname, String testrunid, Outcome out)
            throws IOException, InterruptedException, LoadAPIException {
        long deadline = System.currentTimeMillis() + 10 * minuteMillis;
        JSONObject analysis = null;
        for (int i = 0; i < resultAttempts && analysis == null; i++) {
            analysis = withRetry(log, deadline, () -> api.getAnalysis(testrunname, testrunid));
            if (analysis == null) {
                Thread.sleep(pollMillis);
            }
        }
        if (analysis == null) {
            throw new AbortException(prefix("no results available for run #" + testrunid));
        }
        JSONObject overall = analysis.optJSONObject("overall");
        if (overall != null && !overall.isNullObject()) {
            putMetric(out, "meanMs", overall.opt("mean"));
            putMetric(out, "p95Ms", overall.opt("p95"));
            putMetric(out, "p99Ms", overall.opt("p99"));
            putMetric(out, "errorRatePct", overall.opt("errorPct"));
            putMetric(out, "rps", overall.opt("rps"));
        }
        JSONArray labels = analysis.optJSONArray("labels");
        if (labels == null || labels.isEmpty()) {
            throw new AbortException(prefix("run #" + testrunid + " has no per-request results to check"));
        }
        Result result = Result.SUCCESS;
        for (Object o : labels) {
            JSONObject l = (JSONObject) o;
            String label = l.optString("label", "?");
            double mean = l.optDouble("mean", Double.NaN);
            double errorPct = l.optDouble("errorPct", 0);
            info(log, "Result: " + label + ": average response time " + mean + " ms, errors " + errorPct + "%");
            result = result.combine(grade(log, label, "error percentage", errorPct, "%", errorUnstableThreshold, errorFailedThreshold));
            if (!Double.isNaN(mean)) {
                result = result.combine(grade(log, label, "average response time", mean, " ms", responseTimeUnstableThreshold, responseTimeFailedThreshold));
            }
        }
        return result;
    }

    private static Result grade(PrintStream log, String label, String metric, double actual, String unit, int unstable, int failed) {
        if (failed >= 0 && actual > failed) {
            info(log, "FAILURE: " + label + ": " + metric + " " + actual + unit + " is greater than " + failed + unit);
            return Result.FAILURE;
        }
        if (unstable >= 0 && actual > unstable) {
            info(log, "UNSTABLE: " + label + ": " + metric + " " + actual + unit + " is greater than " + unstable + unit);
            return Result.UNSTABLE;
        }
        return Result.SUCCESS;
    }

    private JSONObject fetchVerdict(LoadAPI api, PrintStream log, String testrunname, String testrunid)
            throws IOException, InterruptedException, LoadAPIException {
        long deadline = System.currentTimeMillis() + 10 * minuteMillis;
        JSONObject v = null;
        for (int i = 0; i < resultAttempts; i++) {
            v = withRetry(log, deadline, () -> api.getVerdict(testrunname, testrunid));
            if (!v.optBoolean("enabled") || v.optBoolean("metricsAvailable")) {
                return v;
            }
            Thread.sleep(pollMillis);
        }
        return v;
    }

    static Result checkVerdict(PrintStream log, JSONObject v) {
        String verdict = v.optString("verdict", "none");
        List<Object> checks = new ArrayList<>();
        if (v.optJSONArray("checks") != null) {
            checks.addAll(v.getJSONArray("checks"));
        }
        JSONObject cwv = v.optJSONObject("cwv");
        if (cwv != null && cwv.optJSONArray("checks") != null) {
            checks.addAll(cwv.getJSONArray("checks"));
        }
        for (Object o : checks) {
            JSONObject c = (JSONObject) o;
            String unit = c.optString("unit", "").isEmpty() ? "" : " " + c.optString("unit");
            String dir = "max".equals(c.optString("dir")) ? "<=" : ">=";
            info(log, "Verdict check " + (c.optBoolean("pass") ? "PASS" : "FAIL") + ": " + c.optString("label", c.optString("key"))
                    + " " + c.opt("actual") + unit + " (target " + dir + " " + c.opt("target") + unit + ")");
        }
        List<String> unevaluated = new ArrayList<>();
        addAll(unevaluated, v.optJSONArray("unevaluated"));
        if (cwv != null) {
            addAll(unevaluated, cwv.optJSONArray("unevaluated"));
        }

        // Fail closed first: the server answers 'none' both when no thresholds are enabled AND when
        // thresholds are enabled but no metric could be read (no samples), so check that case before 'none'.
        if (!unevaluated.isEmpty()) {
            info(log, "Verdict: FAIL. Thresholds could not be evaluated for this run: " + String.join(", ", unevaluated));
            return Result.FAILURE;
        }
        boolean enabled = v.optBoolean("enabled") || (cwv != null && cwv.optBoolean("enabled"));
        if ("none".equals(verdict) && enabled) {
            info(log, "Verdict: FAIL. Thresholds are enabled on LoadFocus but none could be evaluated for this run");
            return Result.FAILURE;
        }
        if ("none".equals(verdict)) {
            info(log, "Verdict: none. No pass/fail thresholds are enabled for this test on LoadFocus; marking build UNSTABLE");
            return Result.UNSTABLE;
        }
        if ("pass".equals(verdict)) {
            info(log, "Verdict: PASS");
            return Result.SUCCESS;
        }
        info(log, "Verdict: FAIL");
        return Result.FAILURE;
    }

    private static void addAll(List<String> into, JSONArray arr) {
        if (arr != null) {
            for (Object o : arr) {
                into.add(String.valueOf(o));
            }
        }
    }

    private static String describeError(String error) {
        if (error == null) {
            return "unknown error";
        }
        switch (error) {
            case "number-of-test-parallel-exceeded":
                return "too many tests running in parallel for your plan (" + error + ")";
            case "number-of-daily-tests-exceeded":
            case "number-of-yearly-tests-exceeded":
                return "plan test limit reached, see https://loadfocus.com/pricing (" + error + ")";
            case "invalid-api-key":
                return "the API key was rejected (" + error + ")";
            default:
                return error;
        }
    }

    private void validateThresholds() throws AbortException {
        if (errorUnstableThreshold > 100 || errorFailedThreshold > 100) {
            throw new AbortException(prefix("error percentage thresholds must be between 0 and 100"));
        }
        if (!isVerdictUsed() && !hasLocalThresholds()) {
            throw new AbortException(prefix("nothing to check: set at least one threshold or enable useVerdict"));
        }
        if (errorRatePct != null && (errorRatePct < 0 || errorRatePct > 100)) {
            throw new AbortException(prefix("errorRatePct must be between 0 and 100"));
        }
    }

    private boolean hasLocalThresholds() {
        return errorUnstableThreshold >= 0 || errorFailedThreshold >= 0
                || responseTimeUnstableThreshold >= 0 || responseTimeFailedThreshold >= 0;
    }

    private void logConfig(PrintStream log) {
        logThreshold(log, Result.UNSTABLE, "error percentage", errorUnstableThreshold, "%");
        logThreshold(log, Result.FAILURE, "error percentage", errorFailedThreshold, "%");
        logThreshold(log, Result.UNSTABLE, "average response time", responseTimeUnstableThreshold, " ms");
        logThreshold(log, Result.FAILURE, "average response time", responseTimeFailedThreshold, " ms");
        if (isVerdictUsed()) {
            info(log, "Config: build FAILURE if the LoadFocus verdict (thresholds set on loadfocus.com) fails");
        }
    }

    private static void logThreshold(PrintStream log, Result r, String metric, int value, String unit) {
        if (value >= 0) {
            info(log, "Config: build " + r + " if " + metric + " is greater than " + value + unit);
        }
    }

    private String resolveApiKey(Run<?, ?> run) {
        String id = Util.fixEmpty(apiKey);
        if (id == null) {
            id = Util.fixEmpty(getDescriptor().getApiKey());
        }
        if (id == null) {
            return null; // never guess: the first visible credential may belong to another account
        }
        for (LoadCredential c : lookup(run.getParent())) {
            if (id.equals(c.getId())) {
                return c.getApiKey().getPlainText();
            }
        }
        // A standard "Secret text" credential, resolved with the build's own permissions.
        StringCredentials secret = CredentialsProvider.findCredentialById(id, StringCredentials.class, run);
        return secret == null ? null : secret.getSecret().getPlainText();
    }

    static Secret findKey(Item item, String id) {
        for (LoadCredential c : lookup(item)) {
            if (c.getId().equals(id)) {
                return c.getApiKey();
            }
        }
        for (StringCredentials c : lookupSecretTexts(item)) {
            if (c.getId().equals(id)) {
                return c.getSecret();
            }
        }
        return null;
    }

    static List<StringCredentials> lookupSecretTexts(Item item) {
        if (item == null) {
            return CredentialsProvider.lookupCredentialsInItemGroup(StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.<DomainRequirement>emptyList());
        }
        return CredentialsProvider.lookupCredentialsInItem(StringCredentials.class, item, ACL.SYSTEM2, Collections.<DomainRequirement>emptyList());
    }

    static List<LoadCredential> lookup(Item item) {
        if (item == null) {
            return CredentialsProvider.lookupCredentialsInItemGroup(LoadCredential.class, Jenkins.get(), ACL.SYSTEM2, Collections.<DomainRequirement>emptyList());
        }
        return CredentialsProvider.lookupCredentialsInItem(LoadCredential.class, item, ACL.SYSTEM2, Collections.<DomainRequirement>emptyList());
    }

    private static String prefix(String s) {
        return "loadfocus.com: " + s;
    }

    private static void info(PrintStream log, String s) {
        log.println(prefix(s));
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    public String getApiKey() {
        return apiKey;
    }

    @DataBoundSetter
    public void setApiKey(String apiKey) {
        this.apiKey = Util.fixEmpty(apiKey);
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    @Deprecated
    public String getTestName() {
        return testName;
    }

    public Integer getErrorFailedThreshold() {
        return orNull(errorFailedThreshold);
    }

    @DataBoundSetter
    public void setErrorFailedThreshold(Integer value) {
        this.errorFailedThreshold = orUnset(value);
    }

    public Integer getErrorUnstableThreshold() {
        return orNull(errorUnstableThreshold);
    }

    @DataBoundSetter
    public void setErrorUnstableThreshold(Integer value) {
        this.errorUnstableThreshold = orUnset(value);
    }

    public Integer getResponseTimeFailedThreshold() {
        return orNull(responseTimeFailedThreshold);
    }

    @DataBoundSetter
    public void setResponseTimeFailedThreshold(Integer value) {
        this.responseTimeFailedThreshold = orUnset(value);
    }

    public Integer getResponseTimeUnstableThreshold() {
        return orNull(responseTimeUnstableThreshold);
    }

    @DataBoundSetter
    public void setResponseTimeUnstableThreshold(Integer value) {
        this.responseTimeUnstableThreshold = orUnset(value);
    }

    public boolean isUseVerdict() {
        return useVerdict;
    }

    @DataBoundSetter
    public void setUseVerdict(boolean useVerdict) {
        this.useVerdict = useVerdict;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes > 0 ? timeoutMinutes : DEFAULT_TIMEOUT_MINUTES;
    }

    @DataBoundSetter
    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : DEFAULT_TIMEOUT_MINUTES;
    }

    public boolean isShareReport() {
        return shareReport;
    }

    @DataBoundSetter
    public void setShareReport(boolean shareReport) {
        this.shareReport = shareReport;
    }

    public boolean isTagRun() {
        return tagRun == null || tagRun;
    }

    @DataBoundSetter
    public void setTagRun(boolean tagRun) {
        this.tagRun = tagRun ? null : Boolean.FALSE;
    }

    public String getReleaseTag() {
        return releaseTag;
    }

    @DataBoundSetter
    public void setReleaseTag(String releaseTag) {
        this.releaseTag = Util.fixEmptyAndTrim(releaseTag);
    }

    public Integer getP95Ms() {
        return p95Ms;
    }

    @DataBoundSetter
    public void setP95Ms(Integer p95Ms) {
        this.p95Ms = p95Ms;
    }

    public Integer getP99Ms() {
        return p99Ms;
    }

    @DataBoundSetter
    public void setP99Ms(Integer p99Ms) {
        this.p99Ms = p99Ms;
    }

    public Double getErrorRatePct() {
        return errorRatePct;
    }

    @DataBoundSetter
    public void setErrorRatePct(Double errorRatePct) {
        this.errorRatePct = errorRatePct;
    }

    public Double getMinRps() {
        return minRps;
    }

    @DataBoundSetter
    public void setMinRps(Double minRps) {
        this.minRps = minRps;
    }

    private static Integer orNull(int v) {
        return v < 0 ? null : v;
    }

    private static int orUnset(Integer v) {
        return v == null || v < 0 ? -1 : v;
    }

    /** Builds saved before timeoutMinutes existed load it as 0. */
    protected Object readResolve() {
        if (timeoutMinutes <= 0) {
            timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
        }
        return this;
    }

    @Override
    public LoadPerformancePublisherDescriptor getDescriptor() {
        return (LoadPerformancePublisherDescriptor) super.getDescriptor();
    }

    @Extension
    public static class LoadPerformancePublisherDescriptor extends BuildStepDescriptor<Publisher> {
        private String apiKey;

        public LoadPerformancePublisherDescriptor() {
            super(LoadPublisher.class);
            load();
        }

        public FormValidation doCheckErrorUnstableThreshold(@QueryParameter String value) {
            return checkPercent(value);
        }

        public FormValidation doCheckErrorFailedThreshold(@QueryParameter String value) {
            return checkPercent(value);
        }

        public FormValidation doCheckResponseTimeUnstableThreshold(@QueryParameter String value) {
            return checkNonNegative(value);
        }

        public FormValidation doCheckResponseTimeFailedThreshold(@QueryParameter String value) {
            return checkNonNegative(value);
        }

        private static FormValidation checkPercent(String value) {
            FormValidation v = checkNonNegative(value);
            if (v.kind == FormValidation.Kind.OK && Util.fixEmptyAndTrim(value) != null && Integer.parseInt(value.trim()) > 100) {
                return FormValidation.error("Value should be in this range: 0 - 100");
            }
            return v;
        }

        private static FormValidation checkNonNegative(String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.ok("Leave empty to skip this threshold");
            }
            try {
                return Integer.parseInt(value.trim()) < 0 ? FormValidation.error("Value cannot be negative") : FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Not a number");
            }
        }

        // Used by config.jelly to display the test list.
        @POST
        public ListBoxModel doFillTestIdItems(@AncestorInPath Item item, @QueryParameter String apiKey) {
            ListBoxModel items = new ListBoxModel();
            if (!canConfigure(item)) {
                return items;
            }
            if (Util.fixEmpty(apiKey) == null) {
                apiKey = getApiKey();
            }
            Secret apiKeyValue = Util.fixEmpty(apiKey) == null ? null : findKey(item, apiKey);
            if (apiKeyValue == null) {
                items.add("No API Key", "-1");
                return items;
            }
            try {
                List<Map<String, String>> testList = new LoadAPI(baseUrl, apiKeyValue.getPlainText()).getTestList();
                if (testList == null) {
                    items.add("Invalid API key", "-1");
                } else if (testList.isEmpty()) {
                    items.add("No tests - create at least one test", "-1");
                } else {
                    for (Map<String, String> test : testList) {
                        items.add(test.get("testrunname") + " #" + test.get("testrunid"), test.get("testrunname"));
                    }
                }
            } catch (IOException | LoadAPIException e) {
                items.add("Could not load tests: " + e.getMessage(), "-1");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return items;
        }

        @POST
        public ListBoxModel doFillApiKeyItems(@AncestorInPath Item item) {
            ListBoxModel items = new ListBoxModel();
            if (!canConfigure(item)) {
                return items;
            }
            Set<String> seen = new HashSet<>();
            if (item instanceof Job && !getCredentials(Jenkins.get()).isEmpty() && Util.fixEmpty(getApiKey()) != null) {
                items.add("Default API Key", "");
            }
            for (LoadCredential c : lookup(item)) {
                if (seen.add(c.getId())) {
                    items.add(Util.fixEmpty(c.getDescription()) != null ? c.getDescription() : c.getId(), c.getId());
                }
            }
            for (StringCredentials c : lookupSecretTexts(item)) {
                if (seen.add(c.getId())) {
                    items.add("Secret text: " + (Util.fixEmpty(c.getDescription()) != null ? c.getDescription() : c.getId()), c.getId());
                }
            }
            return items;
        }

        private static boolean canConfigure(Item item) {
            return item == null ? Jenkins.get().hasPermission(Jenkins.ADMINISTER) : item.hasPermission(Item.CONFIGURE);
        }

        public List<LoadCredential> getCredentials(Object scope) {
            List<LoadCredential> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Item item = scope instanceof Item ? (Item) scope : null;
            for (LoadCredential c : lookup(item)) {
                if (seen.add(c.getId())) {
                    result.add(c);
                }
            }
            return result;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Load Testing by LoadFocus.com";
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject formData) {
            apiKey = formData.optString("apiKey");
            save();
            return true;
        }

        public String getApiKey() {
            List<LoadCredential> credentials = lookup(null);
            if (Util.fixEmpty(apiKey) == null && !credentials.isEmpty()) {
                return credentials.get(0).getId();
            }
            if (credentials.size() == 1) {
                return credentials.get(0).getId();
            }
            for (LoadCredential c : credentials) {
                if (c.getId().equals(apiKey)) {
                    return apiKey;
                }
            }
            // API key is not valid any more
            return "";
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
