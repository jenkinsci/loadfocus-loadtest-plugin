package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.loadfocus.jenkins.api.LoadAPI;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.*;

public class LoadPublisher extends Notifier {
	private String apiKey;
	private String testId = "";
	private String testName = "";
    private int errorFailedThreshold = 0;
    private int errorUnstableThreshold = 0;
    private int responseTimeFailedThreshold = 0;
    private int responseTimeUnstableThreshold = 0;
    private PrintStream logger;
    static final String baseApiUri = "https://loadfocus.com/";

	@DataBoundConstructor
    public LoadPublisher(String apiKey,
                         String testId,
                         int errorFailedThreshold,
                         int errorUnstableThreshold,
                         int responseTimeFailedThreshold,
                         int responseTimeUnstableThreshold) {
        this.apiKey = apiKey;
        this.errorFailedThreshold = errorFailedThreshold;
        this.errorUnstableThreshold = errorUnstableThreshold;
        this.responseTimeFailedThreshold = responseTimeFailedThreshold;
        this.responseTimeUnstableThreshold = responseTimeUnstableThreshold;
        this.testId = testId;
    }
	
	@Override
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
		logger = listener.getLogger();
        Result result;
        String session;
        String testrunname = getTestId();
        logInfo("Test Started: " + testrunname);
        if ((result = validateParameters(logger)) != Result.SUCCESS) {
            return true;
        }
        String apiKeyId = StringUtils.defaultIfEmpty(getApiKey(), getDescriptor().getApiKey());
        String apiKey = null;
        for (LoadCredential c : CredentialsProvider
                .lookupCredentials(LoadCredential.class, build.getProject(), ACL.SYSTEM)) {
            if (StringUtils.equals(apiKeyId, c.getId())) {
                apiKey = c.getApiKey().getPlainText();
                break;
            }
        }
        
        LoadAPI loadApi = new LoadAPI(apiKey);
        JSONObject resultObj = loadApi.runTest(getTestId());

        if(resultObj != null && resultObj.get("error") != null && resultObj.get("error").toString().equalsIgnoreCase("missing-plan")) {
            logInfo("Something went wrong, please try again.");
            logInfo("View more details: " +  baseApiUri + "tests");
            result = Result.NOT_BUILT;
            return false;
        }

        if(resultObj != null && resultObj.get("error") != null && resultObj.get("error").toString().equalsIgnoreCase("number-of-test-parallel-exceeded")) {
            logInfo("Number of parallel tests exceeded.");
            logInfo("View more details: " +  baseApiUri + "tests");
            result = Result.NOT_BUILT;
            return false;
        }

        if(resultObj != null && resultObj.get("error") != null && resultObj.get("error").toString().equalsIgnoreCase("number-of-daily-tests-exceeded")) {
            logInfo("Number of daily tests exceeded. Upgrade to unlock limits https://loadfocus.com/pricing.");
            logInfo("View more details: " +  baseApiUri + "tests");
            result = Result.NOT_BUILT;
            return false;
        }

        if(resultObj != null && resultObj.get("error") != null && resultObj.get("error").toString().equalsIgnoreCase("number-of-yearly-tests-exceeded")) {
            logInfo("Number of yearly tests exceeded. Upgrade to unlock limits https://loadfocus.com/pricing.");
            logInfo("View more details: " +  baseApiUri + "tests");
            result = Result.NOT_BUILT;
            return false;
        }

        JSONObject configObj = loadApi.retrieveConfig(getTestId());
        String testrunid = (String) configObj.get("testrunid");

        if (testrunname.equals("") ||  testrunid.equals("")) {
        	logInfo("Invalid test information");
            logInfo("View more details: " +  baseApiUri + "tests");
        	result = Result.NOT_BUILT;
            return false;
        }

        int lastPrint = 0;
        int interval = 5;
        JSONObject state;
        while (true) {
            state = loadApi.getState(testrunname, testrunid);

            String testrunnameState = state.get("testrunname").toString();
            String testrunidState = state.get("testrunid").toString();
            String currentState = state.get("state").toString();

            if(!testrunname.equalsIgnoreCase(testrunnameState) || !testrunid.equalsIgnoreCase(testrunidState)){
                logInfo("APIs return invalid test results");
                logInfo("View more details: " +  baseApiUri + "tests");
            	result = Result.NOT_BUILT;
                return false;
            }

            if (currentState.equalsIgnoreCase("initializing") ||
                currentState.equalsIgnoreCase("hardware_build") ||
                currentState.equalsIgnoreCase("provisioning") ||
                currentState.equalsIgnoreCase("software_build") ||
                currentState.equalsIgnoreCase("software_install") ||
                currentState.equalsIgnoreCase("pending_execution") ||
                currentState.equalsIgnoreCase("running")
            ) {

                if(!currentState.equalsIgnoreCase("running")) {
                    logInfo("Test Starting: waiting for test to start " + lastPrint + " sec");
                } else{
                    logInfo("Test Running: waiting for test results " + lastPrint + " sec");
                }

        		if (lastPrint > 600000) {
        			logInfo("API doesn't return test results");
                	result = Result.NOT_BUILT;
                    logInfo("View more details: " +  baseApiUri + "tests");
                    return false;
        		} else {
        			lastPrint = lastPrint + interval;
        			Thread.sleep(interval * 1000);
        		}
        	} else {
        		break;
        	}
        }

        Thread.sleep(3 * 1000);

        JSONArray labelsArray = loadApi.getLabels(testrunname, testrunid, apiKey);

        int countErrorFail = 0;
        int countErrorUnstable = 0;
        int countTimeFail = 0;
        int countTimeUnstable = 0;

        for (int i = 0; i < labelsArray.size(); i++) {
            Map<String, Integer> countResponses;
            String label = labelsArray.getJSONObject(i).get("label").toString();
            countResponses = checkResultForLabels(loadApi, testrunname, testrunid, state, label, apiKey);

            countErrorFail = countErrorFail + countResponses.get("countErrorFail");
            countErrorUnstable = countErrorUnstable + countResponses.get("countErrorUnstable");
            countTimeFail = countTimeFail + countResponses.get("countTimeFail");
            countTimeUnstable = countTimeUnstable + countResponses.get("countTimeUnstable");
        }

        logInfo("View more details: " +  baseApiUri + "tests-print?testrunname="+ testrunname +"&testrunid="+ testrunid +"&apikey="+ apiKey);

        if(countErrorFail > 0){
            result = Result.FAILURE;
        } else if(countErrorUnstable > 0){
            result = Result.UNSTABLE;
        } else if(countTimeFail > 0){
            result = Result.FAILURE;
        } else if(countTimeUnstable > 0){
            result = Result.UNSTABLE;
        }

        LoadBuildAction action = new LoadBuildAction(build, testrunname, testrunid, apiKey);
        build.getActions().add(action);
        build.setResult(result);

        Thread.sleep(2 * 1000);

		return true;
	}


	private Map<String, Integer> checkResultForLabels(LoadAPI loadApi, String testrunname, String testrunid, JSONObject state, String label, String apiKey) throws UnsupportedEncodingException {
        JSONArray resultsFinalArray = loadApi.getResultsFinal(testrunname, testrunid, state, label, apiKey);
        JSONObject resultFinalObj = (JSONObject) resultsFinalArray.get(0);

        int countErrorFail = 0;
        int countErrorUnstable = 0;
        int countTimeFail = 0;
        int countTimeUnstable = 0;

        double time = Double.parseDouble(resultFinalObj.get("mean").toString());
        double errPercentTotal = Double.parseDouble(resultFinalObj.get("ep").toString());
        String httprequest =resultFinalObj.get("url").toString();

        double thresholdTolerance = 0.00005;

        logInfo("Test Results: response time " + time + " ms, error percentage " + errPercentTotal + "%, for " + httprequest + "." );

        if (errorFailedThreshold >= 0 && errPercentTotal - errorFailedThreshold > thresholdTolerance) {
            countErrorFail++;
            logInfo("Test Ended: Build " + Result.FAILURE + " on error percentage threshold for " + httprequest + ".");
            logInfo("Test Ended: Error percentage was " + errPercentTotal + "%, build FAILED if error percentage is greater than Failed Threshold of " + errorFailedThreshold + "%");
        } else if (errorUnstableThreshold >= 0 && errPercentTotal - errorUnstableThreshold > thresholdTolerance) {
            countErrorUnstable++;
            logInfo("Test Ended: Build " + Result.UNSTABLE + " on error percentage threshold for " + httprequest + ".");
            logInfo("Test Ended: Error percentage was " + errPercentTotal + "%, build UNSTABLE if error percentage is greater than Unstable Threshold of " + errorUnstableThreshold + "%" + " but smaller than Failed Threshold of " + errorFailedThreshold + " %");
        }

        if (responseTimeFailedThreshold >= 0 && time - responseTimeFailedThreshold > thresholdTolerance) {
            countTimeFail++;
            logInfo("Test Ended: Build " + Result.FAILURE + " on response time threshold for " + httprequest + ". ");
            logInfo("Test Ended: Time was " + time + "ms, build FAILED if time is greater than Failed Threshold of " + responseTimeFailedThreshold + " ms");

        } else if (responseTimeUnstableThreshold >= 0 && time - responseTimeUnstableThreshold > thresholdTolerance) {
            countTimeUnstable++;
            logInfo("Test Ended: Build " + Result.UNSTABLE + " on response time threshold for " + httprequest + ". ");
            logInfo("Test Ended: Time was " + time + "ms, build UNSTABLE if time is greater than Unstable Threshold of " + responseTimeUnstableThreshold + " ms" + " but smaller than Failed Threshold of " + responseTimeFailedThreshold);
        }

        Map<String, Integer> countResponses = new HashMap<>();
        countResponses.put("countErrorFail", countErrorFail);
        countResponses.put("countErrorUnstable", countErrorUnstable);
        countResponses.put("countTimeFail", countTimeFail);
        countResponses.put("countTimeUnstable", countTimeUnstable);

        return countResponses;
    }


	private void logInfo(String str) {
		if (logger != null) {
			logger.println("loadfocus.com: " + str);
		}
	}

	private Result validateParameters(PrintStream logger) {
        Result result = Result.SUCCESS;
        if (errorUnstableThreshold >= 0 && errorUnstableThreshold <= 100) {
        	logInfo("Test Config: Build " + Result.UNSTABLE.toString() + " if errors percentage greater than or equal to "
                    + errorUnstableThreshold + "%");
        } else {
        	logInfo("Test Config: ERROR! percentage should be between 0 to 100");
            result = Result.NOT_BUILT;
        }

        if (errorFailedThreshold >= 0 && errorFailedThreshold <= 100) {
        	logInfo("Test Config: Build " + Result.FAILURE.toString() + " if errors percentage greater than or equal to "
                    + errorFailedThreshold + "%");
        } else {
        	logInfo("Test Config: ERROR! percentage should be between 0 to 100");
            result = Result.NOT_BUILT;
        }

        if (responseTimeUnstableThreshold >= 0) {
        	logInfo("Test Config: Build " + Result.UNSTABLE.toString() + " if response time greater than or equal to "
                    + responseTimeUnstableThreshold + "ms");
        } else {
            logger.println("Test Config: ERROR! percentage should be greater than or equal to 0");
            result = Result.NOT_BUILT;
        }

        if (responseTimeFailedThreshold >= 0) {
        	logInfo("Test Config: Build " + Result.FAILURE.toString() + " if response time greater than or equal to "
                    + responseTimeFailedThreshold + "ms");
        } else {
        	logInfo("Test Config: ERROR! percentage should be greater than or equal to 0");
            result = Result.NOT_BUILT;
        }
        return result;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	public String getApiKey() {
        return apiKey;
    }

	public int getResponseTimeFailedThreshold() {
        return responseTimeFailedThreshold;
    }

    public void setResponseTimeFailedThreshold(int responseTimeFailedThreshold) {
        this.responseTimeFailedThreshold = responseTimeFailedThreshold;
    }

    public int getResponseTimeUnstableThreshold() {
        return responseTimeUnstableThreshold;
    }

    public void setResponseTimeUnstableThreshold(int responseTimeUnstableThreshold) {
        this.responseTimeUnstableThreshold = responseTimeUnstableThreshold;
    }

    public int getErrorFailedThreshold() {
        return errorFailedThreshold;
    }

    public void setErrorFailedThreshold(int errorFailedThreshold) {
        this.errorFailedThreshold = Math.max(0, Math.min(errorFailedThreshold, 100));
    }

    public int getErrorUnstableThreshold() {
        return errorUnstableThreshold;
    }

    public void setErrorUnstableThreshold(int errorUnstableThreshold) {
        this.errorUnstableThreshold = Math.max(0, Math.min(errorUnstableThreshold,
                100));
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName){
        this.testName = testName;
    }

	@Override
    public LoadPerformancePublisherDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final LoadPerformancePublisherDescriptor DESCRIPTOR = new LoadPerformancePublisherDescriptor();

	public static final class DescriptorImpl
    	extends LoadPerformancePublisherDescriptor {
	}

	public static class LoadPerformancePublisherDescriptor extends BuildStepDescriptor<Publisher> {
		private String apiKey;

        public LoadPerformancePublisherDescriptor() {
            super(LoadPublisher.class);
            load();
        }

     // Used by config.jelly to display the test list.
        public ListBoxModel doFillTestIdItems(@QueryParameter String apiKey) throws FormValidation {
            if (StringUtils.isBlank(apiKey)) {
                apiKey = getApiKey();
            }

            Secret apiKeyValue = null;
            Item item = Stapler.getCurrentRequest().findAncestorObject(Item.class);
            for (LoadCredential c : CredentialsProvider
                    .lookupCredentials(LoadCredential.class, item, ACL.SYSTEM)) {
                if (StringUtils.equals(apiKey, c.getId())) {
                	apiKeyValue = c.getApiKey();
                    break;
                }
            }
            ListBoxModel items = new ListBoxModel();
            if (apiKeyValue == null) {
                items.add("No API Key", "-1");
            } else {
	            LoadAPI lda = new LoadAPI(apiKeyValue.getPlainText());

	            try {
	                List<Map<String, String>> testList = lda.getTestList();
	                if (testList == null){
	                    items.add("Invalid API key ", "-1");
	                } else if (testList.isEmpty()){
	                    items.add("No tests - create at least one test", "-1");
	                } else {
	                    for (Map<String, String> test : testList) {
	                        items.add(test.get("testrunname") + " #" + test.get("testrunid"), test.get("testrunname"));
	                    }
	                }
	            } catch (Exception e) {
	                throw FormValidation.error(e.getMessage(), e);
	            }
            }
            return items;
        }

        public ListBoxModel doFillApiKeyItems() {
            ListBoxModel items = new ListBoxModel();
            Set<String> apiKeys = new HashSet<String>();

            Item item = Stapler.getCurrentRequest().findAncestorObject(Item.class);
            if (item instanceof Job) {
                List<LoadCredential> global = CredentialsProvider
                        .lookupCredentials(LoadCredential.class, Jenkins.getInstance(), ACL.SYSTEM);
                if (!global.isEmpty() && !StringUtils.isEmpty(getApiKey())) {
                    items.add("Default API Key", "");
                }
            }
            for (LoadCredential c : CredentialsProvider
                    .lookupCredentials(LoadCredential.class, item, ACL.SYSTEM)) {
                String id = c.getId();
                if (!apiKeys.contains(id)) {
                    items.add(StringUtils.defaultIfEmpty(c.getDescription(), id), id);
                    apiKeys.add(id);
                }
            }
            return items;
        }

        public List<LoadCredential> getCredentials(Object scope) {
            List<LoadCredential> result = new ArrayList<LoadCredential>();
            Set<String> apiKeys = new HashSet<String>();

            Item item = scope instanceof Item ? (Item) scope : null;
            for (LoadCredential c : CredentialsProvider
                    .lookupCredentials(LoadCredential.class, item, ACL.SYSTEM)) {
                String id = c.getId();
                if (!apiKeys.contains(id)) {
                    result.add(c);
                    apiKeys.add(id);
                }
            }
            return result;
        }

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Load Testing by LoadFocus.com";
		}

		@Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            apiKey = formData.optString("apiKey");
            save();
            return true;
        }

		public String getApiKey() {
            List<LoadCredential> credentials = CredentialsProvider
                    .lookupCredentials(LoadCredential.class, Jenkins.getInstance(), ACL.SYSTEM);
            if (StringUtils.isBlank(apiKey) && !credentials.isEmpty()) {
                return credentials.get(0).getId();
            }
            if (credentials.size() == 1) {
                return credentials.get(0).getId();
            }
            for (LoadCredential c: credentials) {
                if (StringUtils.equals(c.getId(), apiKey)) {
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
