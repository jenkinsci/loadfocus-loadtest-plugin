package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.loadfocus.jenkins.api.LoadAPI;
import com.loadfocus.jenkins.impl.LoadCredentialImpl;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class LoadPublisherTest {
    private JenkinsRule j;
    private FakeLoadFocus lf;
    private final String credId = new LoadCredentialImpl(FakeLoadFocus.KEY, "test key").getId();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        lf = new FakeLoadFocus();
        LoadPublisher.baseUrl = lf.baseUrl();
        LoadPublisher.pollMillis = 10;
        LoadPublisher.minuteMillis = 60_000;
        LoadPublisher.resultAttempts = 3;
        SystemCredentialsProvider.getInstance().getCredentials().add(new LoadCredentialImpl(FakeLoadFocus.KEY, "test key"));
        SystemCredentialsProvider.getInstance().save();
    }

    @AfterEach
    void tearDown() {
        lf.close();
        LoadPublisher.baseUrl = LoadAPI.DEFAULT_BASE_URL;
        LoadPublisher.maxTransientFailures = 60;
    }

    private WorkflowRun pipeline(String step, Result expected) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(step, true));
        return j.assertBuildStatus(expected, p.scheduleBuild2(0));
    }

    @Test
    void pipelineVerdictPassEvaluatesTheNewRun() throws Exception {
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.SUCCESS);
        j.assertLogContains("Run #8 started", b);
        j.assertLogContains("Verdict: PASS", b);
        assertTrue(lf.called("/verdict?testrunname=checkout&testrunid=8"));
        LoadBuildAction a = b.getAction(LoadBuildAction.class);
        assertNotNull(a);
        assertEquals("8", a.getTestrunid());
        assertEquals("pass", a.getVerdict());
        assertEquals(lf.baseUrl() + "tests?testrunname=checkout&testrunid=8", a.getReportUrl());
    }

    @Test
    void apiKeyNeverAppearsInLogOrBuildRecord() throws Exception {
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true, errorFailedThreshold: 5", Result.SUCCESS);
        String log = JenkinsRule.getLog(b);
        assertFalse(log.contains(FakeLoadFocus.KEY), log);
        String buildXml = new String(java.nio.file.Files.readAllBytes(new java.io.File(b.getRootDir(), "build.xml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(buildXml.contains(FakeLoadFocus.KEY));
        assertFalse(lf.requests.stream().anyMatch(r -> r.contains(FakeLoadFocus.KEY)), "key must travel only in the header");
    }

    @Test
    void unknownExecuteErrorFailsWithoutEvaluatingAnOldRun() throws Exception {
        lf.executeStatus = 400;
        lf.executeBody = "{\"command\":\"newtest\",\"error\":\"trial-host-already-tested\",\"trial\":true}";
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("test did not start: trial-host-already-tested", b);
        assertFalse(lf.called("/state"));
        assertFalse(lf.called("/verdict"));
        assertNull(b.getAction(LoadBuildAction.class));
    }

    @Test
    void acceptedButNoNewRunIsNotTreatedAsSuccess() throws Exception {
        lf.executeCreatesRun = false;
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("no new run appeared", b);
        assertFalse(lf.called("/state"));
    }

    @Test
    void failingVerdictFailsBuild() throws Exception {
        lf.verdict = lf.verdict.replace("\"verdict\":\"pass\"", "\"verdict\":\"fail\"").replace("\"pass\":true", "\"pass\":false");
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("Verdict check FAIL: P95 response time 310 ms (target <= 500 ms)", b);
    }

    @Test
    void failingVerdictStopsThePipeline() throws Exception {
        lf.verdict = lf.verdict.replace("\"verdict\":\"pass\"", "\"verdict\":\"fail\"").replace("\"pass\":true", "\"pass\":false");
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true\necho 'DEPLOYING-AFTER-GATE'", Result.FAILURE);
        j.assertLogNotContains("DEPLOYING-AFTER-GATE", b);
        assertNotNull(b.getAction(LoadBuildAction.class), "results link is recorded before failing");
    }

    @Test
    void catchErrorCanHandleAFailingGate() throws Exception {
        lf.verdict = lf.verdict.replace("\"verdict\":\"pass\"", "\"verdict\":\"fail\"").replace("\"pass\":true", "\"pass\":false");
        WorkflowRun b = pipeline("catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {\n"
                + "  loadfocusLoadTest testId: 'checkout', useVerdict: true\n}\necho 'AFTER'", Result.SUCCESS);
        j.assertLogContains("AFTER", b);
    }

    @Test
    void enabledThresholdsWithNothingEvaluatedFailClosed() throws Exception {
        // Real server shape when thresholds are enabled but the run has no metrics: verdict 'none' + unevaluated.
        lf.verdict = "{\"verdict\":\"none\",\"enabled\":true,\"checks\":[],\"metrics\":null,\"metricsAvailable\":false,"
                + "\"unevaluated\":[\"p95Ms\",\"errorRatePct\"],\"cwv\":{\"verdict\":\"none\",\"enabled\":false,\"checks\":[],\"unevaluated\":[]}}";
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("could not be evaluated for this run: p95Ms, errorRatePct", b);
        j.assertLogNotContains("No pass/fail thresholds are enabled", b);
    }

    @Test
    void enabledThresholdsWithVerdictNoneFailClosed() throws Exception {
        lf.verdict = "{\"verdict\":\"none\",\"enabled\":true,\"checks\":[],\"metricsAvailable\":true,\"unevaluated\":[]}";
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("enabled on LoadFocus but none could be evaluated", b);
    }

    @Test
    void maintenancePageWhilePollingIsRetried() throws Exception {
        lf.stateFailures = 3;
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.SUCCESS);
        j.assertLogContains("temporarily unavailable", b);
        j.assertLogContains("Run #8 finished", b);
    }

    @Test
    void persistentApiOutageEventuallyFails() throws Exception {
        LoadPublisher.maxTransientFailures = 3;
        lf.stateFailures = 100;
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("HTTP 503", b);
    }

    @Test
    void concurrentLaunchOfTheSameTestIsRejected() throws Exception {
        lf.executeIncrement = 2; // another build took run 8, ours would be 9: ambiguous
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("another run of this test started at the same time (expected run #8, latest is #9)", b);
        assertFalse(lf.called("/state"));
    }

    @Test
    void unknownRunStopsWaiting() throws Exception {
        LoadPublisher.maxTransientFailures = 3;
        LoadPublisher.minuteMillis = 2_000; // without the cap the build would end on the (short) timeout instead
        lf.states.clear();
        lf.states.add("");
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true, timeoutMinutes: 1", Result.FAILURE);
        j.assertLogContains("reports no state for run #8", b);
    }

    @Test
    void staleCredentialIdIsNotGuessedOrPrinted() throws Exception {
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true, apiKey: 'lfke...zzzzzz'", Result.FAILURE);
        j.assertLogContains("credential was not found", b);
        j.assertLogNotContains("lfke...", b);
        assertFalse(lf.called("/execute"));
    }

    @Test
    void stepReturnsRunVerdictAndMetrics() throws Exception {
        WorkflowRun b = pipeline("def r = loadfocusLoadTest testId: 'checkout', useVerdict: true\n"
                + "echo \"RUN=${r.testrunid} RESULT=${r.result} VERDICT=${r.verdict} P95=${r.metrics.p95Ms} RPS=${r.metrics.rps}\"\n"
                + "echo \"REPORT=${r.reportUrl}\"", Result.SUCCESS);
        j.assertLogContains("RUN=8 RESULT=SUCCESS VERDICT=pass P95=310 RPS=212.9", b);
        j.assertLogContains("REPORT=" + lf.baseUrl() + "tests?testrunname=checkout&testrunid=8", b);
    }

    @Test
    void thresholdsFromTheJenkinsfileAreSavedBeforeTheRunAndImplyTheVerdict() throws Exception {
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', p95Ms: 500, errorRatePct: 1.5", Result.SUCCESS);
        String put = lf.body("/api/v1/loadtests/thresholds");
        assertNotNull(put, "thresholds were not sent");
        net.sf.json.JSONObject sent = net.sf.json.JSONObject.fromObject(put);
        assertEquals("checkout", sent.getString("testrunname"));
        assertTrue(sent.getBoolean("enabled"));
        assertEquals(500, sent.getInt("p95Ms"));
        assertEquals(1.5, sent.getDouble("errorRatePct"));
        assertFalse(sent.has("minRps"), "unset metrics are omitted (the server clears them)");
        assertTrue(lf.indexOf("PUT /api/v1/loadtests/thresholds") < lf.indexOf("/newtest/execute"), "saved before launching");
        assertTrue(lf.called("/verdict"), "managed thresholds imply the verdict check");
        j.assertLogContains("Verdict: PASS", b);
    }

    @Test
    void rejectedThresholdsStopBeforeLaunching() throws Exception {
        lf.thresholdsStatus = 400;
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', p95Ms: 500", Result.FAILURE);
        j.assertLogContains("could not save thresholds: HTTP 400 (invalid-errorRatePct)", b);
        assertFalse(lf.called("/execute"));
    }

    @Test
    void runIsTaggedWithTheBuild() throws Exception {
        pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.SUCCESS);
        String form = lf.body("/api/test/general/run-annotation");
        assertNotNull(form);
        assertTrue(form.contains("testrunname=checkout&testrunid=8&label=Jenkins+p+%231"), form);
    }

    @Test
    void customRunTagIsExpanded() throws Exception {
        pipeline("withEnv(['VERSION=2.4.1']) { loadfocusLoadTest testId: 'checkout', useVerdict: true, releaseTag: 'v${VERSION}' }", Result.SUCCESS);
        assertTrue(lf.body("/api/test/general/run-annotation").contains("label=v2.4.1"));
    }

    @Test
    void taggingCanBeTurnedOffAndItsFailureIsNotFatal() throws Exception {
        pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true, tagRun: false", Result.SUCCESS);
        assertFalse(lf.called("/run-annotation"));
        lf.annotateStatus = 400;
        WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("loadfocusLoadTest testId: 'checkout', useVerdict: true", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Run not tagged", b);
    }

    @Test
    void secretTextCredentialWorks() throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().clear();
        SystemCredentialsProvider.getInstance().getCredentials().add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(
                com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL, "lf-token", "LoadFocus token", hudson.util.Secret.fromString(FakeLoadFocus.KEY)));
        SystemCredentialsProvider.getInstance().save();
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true, apiKey: 'lf-token'", Result.SUCCESS);
        j.assertLogContains("Run #8 started", b);
        j.assertLogNotContains(FakeLoadFocus.KEY, b);
    }

    @Test
    void unstableResultMarksTheStage() throws Exception {
        lf.verdict = "{\"verdict\":\"none\",\"enabled\":false,\"checks\":[],\"metricsAvailable\":false,\"unevaluated\":[]}";
        WorkflowRun b = pipeline("stage('Load') { loadfocusLoadTest testId: 'checkout', useVerdict: true }", Result.UNSTABLE);
        boolean marked = false;
        for (org.jenkinsci.plugins.workflow.graph.FlowNode n : new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner().allNodes(b.getExecution())) {
            org.jenkinsci.plugins.workflow.actions.WarningAction w = n.getPersistentAction(org.jenkinsci.plugins.workflow.actions.WarningAction.class);
            if (w != null && w.getResult() == Result.UNSTABLE) {
                marked = true;
            }
        }
        assertTrue(marked, "the step's node carries a WarningAction(UNSTABLE)");
    }

    @Test
    void unevaluatedThresholdFailsClosed() throws Exception {
        lf.verdict = lf.verdict.replace("\"unevaluated\":[],\"cwv\"", "\"unevaluated\":[\"minRps\"],\"cwv\"");
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("could not be evaluated for this run: minRps", b);
    }

    @Test
    void noThresholdsOnServerIsUnstable() throws Exception {
        lf.verdict = "{\"verdict\":\"none\",\"enabled\":false,\"checks\":[],\"metricsAvailable\":false,\"unevaluated\":[]}";
        pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.UNSTABLE);
    }

    @Test
    void failedRunStateFailsBuild() throws Exception {
        lf.states.clear();
        lf.states.add("finished_failed_to_run");
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true", Result.FAILURE);
        j.assertLogContains("ended with state 'finished_failed_to_run'", b);
    }

    @Test
    void timeoutFailsBuild() throws Exception {
        LoadPublisher.minuteMillis = 50;
        lf.states.clear();
        lf.states.add("running");
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', useVerdict: true, timeoutMinutes: 1", Result.FAILURE);
        j.assertLogContains("did not finish within 1 minutes", b);
    }

    @Test
    void localResponseTimeThresholdMarksUnstable() throws Exception {
        lf.analysis = "{\"samples\":10,\"labels\":[{\"label\":\"https://example.com/\",\"mean\":600,\"errorPct\":0}]}";
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout', responseTimeUnstableThreshold: 500, responseTimeFailedThreshold: 1000", Result.UNSTABLE);
        j.assertLogContains("UNSTABLE: https://example.com/: average response time 600.0 ms is greater than 500 ms", b);
        assertFalse(lf.called("/verdict"), "verdict is opt-in");
    }

    @Test
    void zeroErrorThresholdKeepsOneOneFiveSemantics() throws Exception {
        // 1.1.5 used a strict 'greater than', so a 0% threshold passes a clean run and fails any error.
        pipeline("loadfocusLoadTest testId: 'checkout', errorFailedThreshold: 0", Result.SUCCESS);
        lf.analysis = "{\"samples\":10,\"labels\":[{\"label\":\"u\",\"mean\":10,\"errorPct\":0.5}]}";
        WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
        j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
    }

    @Test
    void nothingToCheckIsRejected() throws Exception {
        WorkflowRun b = pipeline("loadfocusLoadTest testId: 'checkout'", Result.FAILURE);
        j.assertLogContains("nothing to check", b);
        assertFalse(lf.called("/execute"));
    }

    @Test
    void freestylePostBuildActionStillWorks() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        LoadPublisher pub = new LoadPublisher("checkout");
        pub.setApiKey(credId);
        pub.setErrorFailedThreshold(5);
        pub.setUseVerdict(true);
        p.getPublishersList().add(pub);
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Run #8 started", b);
    }

    @Test
    void freestyleConfigRoundTrip() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        // With a single credential the form hides the key picker and the default key is used.
        LoadPublisher pub = new LoadPublisher("checkout");
        pub.setErrorUnstableThreshold(2);
        pub.setResponseTimeFailedThreshold(900);
        pub.setUseVerdict(true);
        pub.setTimeoutMinutes(45);
        p.getPublishersList().add(pub);
        j.configRoundtrip(p);
        LoadPublisher after = p.getPublishersList().get(LoadPublisher.class);
        j.assertEqualDataBoundBeans(pub, after);
        assertNull(after.getErrorFailedThreshold(), "unset thresholds stay unset");
    }

    @Test
    void apiKeyValidationHonoursServerRejection() {
        assertTrue(new LoadAPI(lf.baseUrl(), FakeLoadFocus.KEY).isValidApiKey());
        assertFalse(new LoadAPI(lf.baseUrl(), "wrong-key").isValidApiKey(), "a 403 'invalid' must not read as valid");
        lf.validateStatus = 403;
        assertFalse(new LoadAPI(lf.baseUrl(), FakeLoadFocus.KEY).isValidApiKey());
    }
}
