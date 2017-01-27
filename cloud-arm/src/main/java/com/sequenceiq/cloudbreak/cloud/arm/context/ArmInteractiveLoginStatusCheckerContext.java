package com.sequenceiq.cloudbreak.cloud.arm.context;

import com.sequenceiq.cloudbreak.cloud.credential.CredentialNotifier;
import com.sequenceiq.cloudbreak.cloud.model.ExtendedCloudCredential;

public class ArmInteractiveLoginStatusCheckerContext {

    private Boolean cancelled = false;

    private String deviceCode;

    private final CredentialNotifier credentialNotifier;

    private ExtendedCloudCredential extendedCloudCredential;

    public ArmInteractiveLoginStatusCheckerContext(String deviceCode, ExtendedCloudCredential extendedCloudCredential, CredentialNotifier credentialNotifier) {
        this.deviceCode = deviceCode;
        this.extendedCloudCredential = extendedCloudCredential;
        this.credentialNotifier = credentialNotifier;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public ExtendedCloudCredential getExtendedCloudCredential() {
        return extendedCloudCredential;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
    }

    public CredentialNotifier getCredentialNotifier() {
        return credentialNotifier;
    }
}
