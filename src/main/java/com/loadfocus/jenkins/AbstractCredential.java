package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.BaseCredentials;
import com.cloudbees.plugins.credentials.CredentialsScope;

public abstract  class AbstractCredential extends BaseCredentials implements LoadCredential {

	private static final long serialVersionUID = -7426700608553371938L;

	protected AbstractCredential() {
        super(CredentialsScope.GLOBAL);
    }

    public String getId() {
        final String apiKey = getApiKey().getPlainText();
        // Kept as-is: jobs saved by earlier versions reference credentials by this id.
        return apiKey.substring(0, Math.min(4, apiKey.length())) + "..." + apiKey.substring(Math.max(0, apiKey.length() - 6));
    }
}
