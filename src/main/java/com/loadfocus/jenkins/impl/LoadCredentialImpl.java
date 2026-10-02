package com.loadfocus.jenkins.impl;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.loadfocus.jenkins.AbstractCredential;
import com.loadfocus.jenkins.api.LoadAPI;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.model.Item;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;


public class LoadCredentialImpl extends AbstractCredential {
	private static final long serialVersionUID = 1L;
	private final Secret apiKey;
    private final String description;

    @DataBoundConstructor
    public LoadCredentialImpl(String apiKey, String description) {
        this.apiKey = Secret.fromString(apiKey);
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.LoadCredential_DisplayName();
        }

        @POST
        public FormValidation doTestConnection(@AncestorInPath Item context, @QueryParameter("apiKey") final Secret apiKey) {
            // Folder-scoped credentials are managed from the folder, so check there rather than globally.
            if (context != null) {
                context.checkAnyPermission(CredentialsProvider.CREATE, CredentialsProvider.UPDATE);
            } else {
                Jenkins.get().checkAnyPermission(CredentialsProvider.CREATE, CredentialsProvider.UPDATE);
            }
            return checkLoadKey(apiKey.getPlainText());
        }

        @POST
        public FormValidation doTestExistingConnection(@AncestorInPath Item context, @QueryParameter("apiKey") final Secret apiKey) {
            return doTestConnection(context, apiKey);
        }

        private FormValidation checkLoadKey(final String apiKey) {
        	LoadAPI ldr = new LoadAPI(apiKey);
            if (ldr.isValidApiKey()) {
                return FormValidation.okWithMarkup("Valid API Key");
            } else {
                return FormValidation.errorWithMarkup("Invalid API Key");
            }
        }

    }
    
}
