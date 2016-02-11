package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.Credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;


public interface LoadCredential extends Credentials {

    String getDescription();

    String getId();

    Secret getApiKey();

}