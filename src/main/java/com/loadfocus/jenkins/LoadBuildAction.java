package com.loadfocus.jenkins;

import com.loadfocus.jenkins.api.LoadAPI;
import hudson.model.Run;
import jenkins.model.RunAction2;

/**
 * Links a build to its LoadFocus run (test, run id, report link and verdict). Stores no credentials;
 * fields written by older versions are ignored and dropped when the build is saved again.
 */
public class LoadBuildAction implements RunAction2 {
	private transient Run<?, ?> run;

	private String testrunid;
	private String testrunname;
	private String reportUrl;
	private String verdict;

	public LoadBuildAction(String testrunname, String testrunid, String reportUrl, String verdict) {
		this.testrunname = testrunname;
		this.testrunid = testrunid;
		this.reportUrl = reportUrl;
		this.verdict = verdict;
	}

	@Override
	public void onAttached(Run<?, ?> r) {
		this.run = r;
	}

	@Override
	public void onLoad(Run<?, ?> r) {
		this.run = r;
	}

	public Run<?, ?> getOwner() {
		return run;
	}

	public String getIconFileName() {
		return "/plugin/loadfocus-loadtest/images/icon48.png";
	}

	public String getDisplayName() {
		return "LoadFocus.com Results";
	}

	public String getUrlName() {
		return "loadfocus";
	}

	public String getTestrunid() {
		return testrunid;
	}

	public String getTestrunname() {
		return testrunname;
	}

	public String getVerdict() {
		return verdict;
	}

	/** Public share link when one was created, else the LoadFocus results page (login required). */
	public String getReportUrl() {
		if (reportUrl != null) {
			return reportUrl;
		}
		if (testrunname == null || testrunid == null) {
			return null;
		}
		return LoadAPI.resultsUrl(LoadPublisher.baseUrl, testrunname, testrunid);
	}
}
