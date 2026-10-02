package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.loadfocus.jenkins.api.LoadAPI;
import com.loadfocus.jenkins.impl.LoadCredentialImpl;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Job config, build record and global config exactly as 1.1.5 wrote them. */
@WithJenkins
class LoadPublisherCompatTest {

    @Test
    @LocalData
    void oldDataLoads(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.jenkins.getItemByFullName("old", FreeStyleProject.class);
        assertNotNull(p, "1.1.5 job must load");
        LoadPublisher pub = p.getPublishersList().get(LoadPublisher.class);
        assertNotNull(pub);
        assertEquals("checkout", pub.getTestId());
        assertEquals(5, pub.getErrorFailedThreshold());
        assertEquals(0, pub.getErrorUnstableThreshold(), "0 stays an active threshold");
        assertEquals(1000, pub.getResponseTimeFailedThreshold());
        assertEquals(500, pub.getResponseTimeUnstableThreshold());
        assertEquals(120, pub.getTimeoutMinutes());
        assertFalse(pub.isUseVerdict());

        FreeStyleBuild old = p.getBuildByNumber(1);
        LoadBuildAction a = old.getAction(LoadBuildAction.class);
        assertNotNull(a, "1.1.5 build action must load");
        assertSame(old, a.getOwner());
        assertEquals("5", a.getTestrunid());
        assertEquals(LoadAPI.DEFAULT_BASE_URL + "tests?testrunname=checkout&testrunid=5", a.getReportUrl());

        String page = j.createWebClient().getPage(old, "loadfocus").getWebResponse().getContentAsString();
        assertTrue(page.contains("testrunid=5"));
        assertFalse(page.contains(FakeLoadFocus.KEY), "old stored key must not be rendered");

        old.save(); // what Manage Old Data > Upgrade does
        String xml = Files.readString(new File(old.getRootDir(), "build.xml").toPath(), StandardCharsets.UTF_8);
        assertFalse(xml.contains(FakeLoadFocus.KEY), "re-saving an old build scrubs the stored key");
    }

    @Test
    @LocalData("oldDataLoads")
    void oldJobStillRunsWithTheGlobalDefaultKey(JenkinsRule j) throws Exception {
        try (FakeLoadFocus lf = new FakeLoadFocus()) {
            LoadPublisher.baseUrl = lf.baseUrl();
            LoadPublisher.pollMillis = 10;
            SystemCredentialsProvider.getInstance().getCredentials().add(new LoadCredentialImpl(FakeLoadFocus.KEY, "old key"));
            SystemCredentialsProvider.getInstance().save();
            FreeStyleProject p = j.jenkins.getItemByFullName("old", FreeStyleProject.class);
            FreeStyleBuild b = j.buildAndAssertSuccess(p);
            j.assertLogContains("Run #8 started", b);
            j.assertLogContains("Config: build UNSTABLE if error percentage is greater than 0%", b);
        } finally {
            LoadPublisher.baseUrl = LoadAPI.DEFAULT_BASE_URL;
        }
    }
}
