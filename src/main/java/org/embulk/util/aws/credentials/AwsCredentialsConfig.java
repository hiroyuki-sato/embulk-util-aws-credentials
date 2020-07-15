package org.embulk.util.aws.credentials;

import java.util.Optional;

public interface AwsCredentialsConfig {
    String getAuthMethod();

    void setAuthMethod(String method);

    Optional<String> getAccessKeyId();

    void setAccessKeyId(Optional<String> value);

    Optional<String> getSecretAccessKey();

    void setSecretAccessKey(Optional<String> value);

    Optional<String> getSessionToken();

    void setSessionToken(Optional<String> value);

    Optional<String> getProfileFile();

    void setProfileFile(Optional<String> value);

    Optional<String> getProfileName();

    void setProfileName(Optional<String> value);
}
