package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.loadfocus.jenkins.api.LoadAPI;
import com.loadfocus.jenkins.impl.LoadCredentialImpl;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a REAL load test on loadfocus.com (uses one run from the plan). Skipped unless
 * LF_LIVE_KEY_FILE (path to a file holding the API key) and LF_LIVE_TEST are set:
 * <pre>LF_LIVE_KEY_FILE=... LF_LIVE_TEST=my-test mvn test -Dtest=LiveLoadFocusIT</pre>
 */
@WithJenkins
@EnabledIfEnvironmentVariable(named = "LF_LIVE_TEST", matches = ".+")
class LiveLoadFocusIT {

    @Test
    void realRunWithVerdict(JenkinsRule j) throws Exception {
        String key = Files.readString(Path.of(System.getenv("LF_LIVE_KEY_FILE")), StandardCharsets.UTF_8).trim();
        String test = System.getenv("LF_LIVE_TEST");
        LoadPublisher.baseUrl = LoadAPI.DEFAULT_BASE_URL;
        LoadPublisher.pollMillis = 5_000;

        assertTrue(new LoadAPI(key).isValidApiKey(), "key rejected by /api/v1/key/validate");
        SystemCredentialsProvider.getInstance().getCredentials().add(new LoadCredentialImpl(key, "live"));
        SystemCredentialsProvider.getInstance().save();

        WorkflowJob p = j.createProject(WorkflowJob.class, "live");
        String p95 = System.getenv("LF_LIVE_P95"); // optional: also exercise thresholds managed from Jenkins (rewrites the test's thresholds)
        p.setDefinition(new CpsFlowDefinition(
                "def r = loadfocusLoadTest testId: '" + test.replace("'", "\\'") + "', useVerdict: true, errorFailedThreshold: 100, timeoutMinutes: 30"
                        + (p95 != null ? ", p95Ms: " + Integer.parseInt(p95) : "") + "\n"
                        + "echo \"RETURNED run=${r.testrunid} result=${r.result} verdict=${r.verdict} p95=${r.metrics.p95Ms}\"", true));
        WorkflowRun b = p.scheduleBuild2(0).get();
        String log = JenkinsRule.getLog(b);
        System.out.println("----- live build log -----\n" + log + "\n----- result: " + b.getResult());

        assertFalse(log.contains(key), "API key leaked into the build log");
        assertTrue(log.contains("Run #") && log.contains(" finished"), "run did not complete");
        assertTrue(log.contains("Verdict"), "verdict was not evaluated");
        assertTrue(log.contains("\" tagged ") || log.contains(" tagged \""), "run was not tagged");
        assertTrue(log.contains("RETURNED run="), "step did not return its result");
        assertTrue(b.getResult() != Result.FAILURE || log.contains("Verdict: FAIL") || log.contains("FAILURE:"),
                "build failed for a reason other than its thresholds");
        assertNotNull(b.getAction(LoadBuildAction.class));
    }
}
