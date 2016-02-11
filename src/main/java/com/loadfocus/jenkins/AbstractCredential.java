package com.loadfocus.jenkins;

import com.cloudbees.plugins.credentials.BaseCredentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import org.apache.commons.lang.StringUtils;

public abstract  class AbstractCredential extends BaseCredentials implements LoadCredential {

	private static final long serialVersionUID = -7426700608553371938L;

	protected AbstractCredential() {
        super(CredentialsScope.GLOBAL);
    }

    public String getId() {
        final String apiKey = getApiKey().getPlainText();
        return StringUtils.left(apiKey,4) + "..." + StringUtils.right(apiKey, 6);
    }
}
