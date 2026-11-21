package webpresenter.server.storage.internal;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 connection settings.
 *
 * @param endpoint URL the server uses to reach the S3 store
 * @param publicEndpoint URL put into presigned links, as reachable by browsers and players
 *     (defaults to {@code endpoint}; differs when the server reaches S3 through a Docker network)
 * @param playerEndpoint URL put into links for MediaPlayers (defaults to {@code publicEndpoint})
 * @param region signing region (any value for SeaweedFS)
 * @param bucket bucket holding all media; created on startup if missing
 */
@ConfigurationProperties("webpresenter.storage")
record StorageProperties(
        URI endpoint, URI publicEndpoint, URI playerEndpoint, String region, String accessKey, String secretKey,
        String bucket) {

    StorageProperties {
        if (publicEndpoint == null || publicEndpoint.toString().isBlank()) {
            publicEndpoint = endpoint;
        }
        if (playerEndpoint == null || playerEndpoint.toString().isBlank()) {
            playerEndpoint = publicEndpoint;
        }
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
    }
}
