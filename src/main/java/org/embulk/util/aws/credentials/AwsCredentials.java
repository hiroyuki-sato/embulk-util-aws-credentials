package org.embulk.util.aws.credentials;

import software.amazon.awssdk.auth.AWSCredentials;
import software.amazon.awssdk.auth.AWSCredentialsProvider;
import software.amazon.awssdk.auth.AWSSessionCredentials;
import software.amazon.awssdk.auth.AWSSessionCredentialsProvider;
import software.amazon.awssdk.auth.AnonymousAWSCredentials;
import software.amazon.awssdk.auth.BasicAWSCredentials;
import software.amazon.awssdk.auth.BasicSessionCredentials;
import software.amazon.awssdk.auth.DefaultAWSCredentialsProviderChain;
import software.amazon.awssdk.auth.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.SystemPropertiesCredentialsProvider;
import software.amazon.awssdk.auth.profile.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.profile.ProfilesConfigFile;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.embulk.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class to generate {@link software.amazon.awssdk.auth.AWSCredentialsProvider} from Embulk's task-defining interface.
 */
public abstract class AwsCredentials {
    private AwsCredentials() {
        // No instantiation.
    }

    /**
     * Creates {@link software.amazon.awssdk.auth.AWSCredentialsProvider} from entries prefixed with {@code "aws_"} in task definition.
     *
     * @param task  An entry in Embulk's task defining interface
     * @return {@link software.amazon.awssdk.auth.AWSCredentialsProvider} created
     */
    public static AWSCredentialsProvider getAWSCredentialsProvider(AwsCredentialsTaskWithPrefix task) {
        return getAWSCredentialsProvider("aws_", task);
    }

    /**
     * Creates {@link software.amazon.awssdk.auth.AWSCredentialsProvider} from entries in task definition.
     *
     * @param task  An entry in Embulk's task defining interface
     * @return {@link software.amazon.awssdk.auth.AWSCredentialsProvider} created
     */
    public static AWSCredentialsProvider getAWSCredentialsProvider(AwsCredentialsTask task) {
        return getAWSCredentialsProvider("", task);
    }

    private static AWSCredentialsProvider getAWSCredentialsProvider(String prefix, AwsCredentialsConfig task) {
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
                return new AWSCredentialsProvider() {
                    public AWSCredentials getCredentials() {
                        return new AnonymousAWSCredentials();
                    }

                    public void refresh() {
                    }
                };
            } else {
                reject(task.getSessionToken(), sessionTokenOption);
                reject(task.getProfileFile(), profileFileOption);
                reject(task.getProfileName(), profileNameOption);
                final String accessKeyId = require(task.getAccessKeyId(), "'access_key_id', 'secret_access_key'");
                final String secretAccessKey = require(task.getSecretAccessKey(), "'secret_access_key'");
                final BasicAWSCredentials creds = new BasicAWSCredentials(accessKeyId, secretAccessKey);
                return new AWSCredentialsProvider() {
                    public AWSCredentials getCredentials() {
                        return creds;
                    }

                    public void refresh() {
                    }
                };
            }

        case "env":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return overwriteBasicCredentials(task, new EnvironmentVariableCredentialsProvider().getCredentials());

        case "instance":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return createInstanceProfileCredentialsProvider();

        case "profile":
        {
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);

            String profileName = task.getProfileName().orElse("default");
            ProfileCredentialsProvider provider;
            if (task.getProfileFile().isPresent()) {
                final Path profileFilePath = Paths.get(task.getProfileFile().get());
                final ProfilesConfigFile file = new ProfilesConfigFile(profileFilePath.toFile());
                provider = new ProfileCredentialsProvider(file, profileName);
            } else {
                provider = new ProfileCredentialsProvider(profileName);
            }

            return overwriteBasicCredentials(task, provider.getCredentials());
        }

        case "properties":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return overwriteBasicCredentials(task, new SystemPropertiesCredentialsProvider().getCredentials());

        case "anonymous":
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return new AWSCredentialsProvider() {
                public AWSCredentials getCredentials() {
                    return new AnonymousAWSCredentials();
                }

                public void refresh() {
                }
            };

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

        case "default":
        {
            reject(task.getAccessKeyId(), accessKeyIdOption);
            reject(task.getSecretAccessKey(), secretAccessKeyOption);
            reject(task.getSessionToken(), sessionTokenOption);
            reject(task.getProfileFile(), profileFileOption);
            reject(task.getProfileName(), profileNameOption);
            return new DefaultAWSCredentialsProviderChain();
        }

        default:
            throw new ConfigException(String.format("Unknown auth_method '%s'. Supported methods are basic, instance, profile, properties, anonymous, session and default.",
                        task.getAuthMethod()));
        }
    }

    private static AWSCredentialsProvider overwriteBasicCredentials(AwsCredentialsConfig task, final AWSCredentials creds) {
        return new AWSCredentialsProvider() {
            public AWSCredentials getCredentials() {
                return creds;
            }

            public void refresh() {
            }
        };
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

    @SuppressWarnings("deprecation")
    private static InstanceProfileCredentialsProvider createInstanceProfileCredentialsProvider() {
        return new InstanceProfileCredentialsProvider();
    }

    private static final Logger log = LoggerFactory.getLogger(AwsCredentials.class);
}
