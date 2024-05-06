package org.embulk.util.aws.credentials;

//conflict
//import software.amazon.awssdk.auth.credentials.AwsCredentials;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.embulk.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSupplier;


/**
 * A utility class to generate {@link software.amazon.awssdk.auth.credentials.AwsCredentialsProvider} from Embulk's task-defining interface.
 */
public abstract class AwsCredentials {
    private AwsCredentials() {
        // No instantiation.
    }

    /**
     * Creates {@link software.amazon.awssdk.auth.credentials.AwsCredentialsProvider} from entries prefixed with {@code "aws_"} in task definition.
     *
     * @param task  An entry in Embulk's task defining interface
     * @return {@link software.amazon.awssdk.auth.credentials.AwsCredentialsProvider} created
     */
    public static AwsCredentialsProvider getAWSCredentialsProvider(AwsCredentialsTaskWithPrefix task) {
        return getAWSCredentialsProvider("aws_", task);
    }

    /**
     * Creates {@link software.amazon.awssdk.auth.credentials.AwsCredentialsProvider} from entries in task definition.
     *
     * @param task  An entry in Embulk's task defining interface
     * @return {@link software.amazon.awssdk.auth.credentials.AwsCredentialsProvider} created
     */
    public static AwsCredentialsProvider getAWSCredentialsProvider(AwsCredentialsTask task) {
        return getAWSCredentialsProvider("", task);
    }

    private static AwsCredentialsProvider getAWSCredentialsProvider(String prefix, AwsCredentialsConfig task) {
        String authMethodOption = prefix + "auth_method";
        String sessionTokenOption = prefix + "session_token";
        String profileFileOption = prefix + "profile_file";
        String profileNameOption = prefix + "profile_name";
        String accessKeyIdOption = prefix + "access_key_id";
        String secretAccessKeyOption = prefix + "secret_access_key";

        switch (task.getAuthMethod()) {
        case "basic":
            // for backward compatibility
            if (!task.getAccessKeyId().isPresent() && !task.getAccessKeyId().isPresent()) {
                log.warn("Both '{}' and '{}' are not set. Assuming that '{}: anonymous' option is set.",
                        accessKeyIdOption, secretAccessKeyOption, authMethodOption);
                log.warn("If you intentionally use anonymous authentication, please set 'auth_method: anonymous' option.");
                log.warn("This behavior will be removed in a future release.");
                reject(task.getSessionToken(), sessionTokenOption);
                reject(task.getProfileFile(), profileFileOption);
                reject(task.getProfileName(), profileNameOption);
                return AnonymousCredentialsProvider.create();
            } else {
                reject(task.getSessionToken(), sessionTokenOption);
                reject(task.getProfileFile(), profileFileOption);
                reject(task.getProfileName(), profileNameOption);
                final String accessKeyId = require(task.getAccessKeyId(), "'access_key_id', 'secret_access_key'");
                final String secretAccessKey = require(task.getSecretAccessKey(), "'secret_access_key'");
                return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
            }

        case "env":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return EnvironmentVariableCredentialsProvider.create();

        case "instance":
            throw new ConfigException("deprecated");

        case "profile":
        {
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);

            String profileName = task.getProfileName().orElse("default");
            ProfileCredentialsProvider provider;
            if (task.getProfileFile().isPresent()) {
                final Path profileFilePath = Paths.get(task.getProfileFile().get());
                return ProfileCredentialsProvider
                        .builder()
                        .profileFile(ProfileFileSupplier.reloadWhenModified(profileFilePath,ProfileFile.Type.CONFIGURATION))
                        .profileName(profileName)
                        .build();
            }
            return ProfileCredentialsProvider.create(profileName);
        }

        case "properties":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return SystemPropertyCredentialsProvider.create();

        case "anonymous":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return AnonymousCredentialsProvider.create();

            /* TODO: Raise Exception
        case "session":
        {
            String accessKeyId = require(task.getAccessKeyId(),
                    "'" + accessKeyIdOption + "', '" + secretAccessKeyOption + "', '" + sessionTokenOption + "'");
            String secretAccessKey = require(task.getSecretAccessKey(),
                    "'" + secretAccessKeyOption + "', '" + sessionTokenOption + "'");
            String sessionToken = require(task.getSessionToken(),
                    "'" + sessionTokenOption + "'");
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            final AWSSessionCredentials creds = new BasicSessionCredentials(accessKeyId, secretAccessKey, sessionToken);
            return new AWSSessionCredentialsProvider() {
                public AWSSessionCredentials getCredentials() {
                    return creds;
                }

                public void refresh() {
                }
            };
        }
        */

        case "default":
        {
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return DefaultCredentialsProvider.create();
        }

        default:
            throw new ConfigException(String.format("Unknown auth_method '%s'. Supported methods are basic, instance, profile, properties, anonymous, session and default.",
                        task.getAuthMethod()));
        }
    }

    private static <T> T require(Optional<T> value, String message) {
        if (value.isPresent()) {
            return value.get();
        } else {
            throw new ConfigException("Required option is not set: " + message);
        }
    }

    private static <T> void reject(Optional<T> value, String message) {
        if (value.isPresent()) {
            throw new ConfigException("Invalid option is set: " + message);
        }
    }

    /*
    @SuppressWarnings("deprecation")
    private static InstanceProfileCredentialsProvider createInstanceProfileCredentialsProvider() {
        return new InstanceProfileCredentialsProvider();
    }
    */

    private static final Logger log = LoggerFactory.getLogger(AwsCredentials.class);
}
