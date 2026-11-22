package webpresenter.server.storage.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class S3StorageConfig {

    // Path-style addressing (http://host/bucket/key): self-hosted stores have no per-bucket DNS.
    private static final S3Configuration PATH_STYLE = S3Configuration.builder().pathStyleAccessEnabled(true).build();

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .endpointOverride(props.endpoint())
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(PATH_STYLE)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties props) {
        return presigner(props, props.publicEndpoint());
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3PlayerPresigner(StorageProperties props) {
        return presigner(props, props.playerEndpoint());
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3WorkerPresigner(StorageProperties props) {
        return presigner(props, props.workerEndpoint());
    }

    private static S3Presigner presigner(StorageProperties props, java.net.URI endpoint) {
        return S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(PATH_STYLE)
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties props) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
}
