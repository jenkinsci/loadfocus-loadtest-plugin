package com.loadfocus.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline step {@code loadfocusLoadTest}: runs a LoadFocus cloud load test and returns
 * {@code [testrunname, testrunid, result, verdict, reportUrl, metrics]}. Fails the step on FAILURE,
 * marks build and stage UNSTABLE on UNSTABLE. Same options as the Freestyle post-build action.
 */
public class LoadFocusStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String testId;
    private String apiKey;
    private Integer errorFailedThreshold;
    private Integer errorUnstableThreshold;
    private Integer responseTimeFailedThreshold;
    private Integer responseTimeUnstableThreshold;
    private boolean useVerdict;
    private int timeoutMinutes = LoadPublisher.DEFAULT_TIMEOUT_MINUTES;
    private boolean shareReport;
    private boolean tagRun = true;
    private String releaseTag;
    private Integer p95Ms;
    private Integer p99Ms;
    private Double errorRatePct;
    private Double minRps;

    @DataBoundConstructor
    public LoadFocusStep(String testId) {
        this.testId = Util.fixNull(testId).trim();
    }

    LoadPublisher toPublisher() {
        LoadPublisher p = new LoadPublisher(testId);
        p.setApiKey(apiKey);
        p.setErrorFailedThreshold(errorFailedThreshold);
        p.setErrorUnstableThreshold(errorUnstableThreshold);
        p.setResponseTimeFailedThreshold(responseTimeFailedThreshold);
        p.setResponseTimeUnstableThreshold(responseTimeUnstableThreshold);
        p.setUseVerdict(useVerdict);
        p.setTimeoutMinutes(timeoutMinutes);
        p.setShareReport(shareReport);
        p.setTagRun(tagRun);
        p.setReleaseTag(releaseTag);
        p.setP95Ms(p95Ms);
        p.setP99Ms(p99Ms);
        p.setErrorRatePct(errorRatePct);
        p.setMinRps(minRps);
        return p;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {
        private static final long serialVersionUID = 1L;
        private final LoadFocusStep step;

        Execution(LoadFocusStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext ctx = getContext();
            Run<?, ?> run = ctx.get(Run.class);
            TaskListener listener = ctx.get(TaskListener.class);
            EnvVars env = ctx.get(EnvVars.class);
            LoadPublisher.Outcome o = step.toPublisher().runTest(run, env, listener);
            if (o.result.isWorseThan(Result.SUCCESS)) {
                listener.getLogger().println("loadfocus.com: Marking build and stage " + o.result);
                run.setResult(o.result);
                FlowNode node = ctx.get(FlowNode.class);
                if (node != null) {
                    node.addOrReplaceAction(new WarningAction(o.result).withMessage("LoadFocus load test result: " + o.result));
                }
            }
            return o.toMap();
        }
    }

    public String getTestId() {
        return testId;
    }

    public String getApiKey() {
        return apiKey;
    }

    @DataBoundSetter
    public void setApiKey(String apiKey) {
        this.apiKey = Util.fixEmpty(apiKey);
    }

    public Integer getErrorFailedThreshold() {
        return errorFailedThreshold;
    }

    @DataBoundSetter
    public void setErrorFailedThreshold(Integer v) {
        this.errorFailedThreshold = v;
    }

    public Integer getErrorUnstableThreshold() {
        return errorUnstableThreshold;
    }

    @DataBoundSetter
    public void setErrorUnstableThreshold(Integer v) {
        this.errorUnstableThreshold = v;
    }

    public Integer getResponseTimeFailedThreshold() {
        return responseTimeFailedThreshold;
    }

    @DataBoundSetter
    public void setResponseTimeFailedThreshold(Integer v) {
        this.responseTimeFailedThreshold = v;
    }

    public Integer getResponseTimeUnstableThreshold() {
        return responseTimeUnstableThreshold;
    }

    @DataBoundSetter
    public void setResponseTimeUnstableThreshold(Integer v) {
        this.responseTimeUnstableThreshold = v;
    }

    public boolean isUseVerdict() {
        return useVerdict;
    }

    @DataBoundSetter
    public void setUseVerdict(boolean useVerdict) {
        this.useVerdict = useVerdict;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    @DataBoundSetter
    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : LoadPublisher.DEFAULT_TIMEOUT_MINUTES;
    }

    public boolean isShareReport() {
        return shareReport;
    }

    @DataBoundSetter
    public void setShareReport(boolean shareReport) {
        this.shareReport = shareReport;
    }

    public boolean isTagRun() {
        return tagRun;
    }

    @DataBoundSetter
    public void setTagRun(boolean tagRun) {
        this.tagRun = tagRun;
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

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "loadfocusLoadTest";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run a LoadFocus.com load test";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
