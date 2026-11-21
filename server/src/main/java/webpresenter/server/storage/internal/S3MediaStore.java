package webpresenter.server.storage.internal;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import webpresenter.server.storage.MediaStore;
import webpresenter.server.storage.PresignedUrl;

@Component
class S3MediaStore implements MediaStore, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(S3MediaStore.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Presigner playerPresigner;
    private final String bucket;

    S3MediaStore(S3Client s3, @org.springframework.beans.factory.annotation.Qualifier("s3Presigner") S3Presigner presigner,
            @org.springframework.beans.factory.annotation.Qualifier("s3PlayerPresigner") S3Presigner playerPresigner,
            StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.playerPresigner = playerPresigner;
        this.bucket = props.bucket();
    }

    /** Creates the media bucket on first start. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("created media bucket '{}'", bucket);
        }
    }

    @Override
    public void put(String key, Path file, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(file));
    }

    @Override
    public PresignedUrl presignedDownload(String key, Duration validity) {
        return presign(presigner, key, validity);
    }

    @Override
    public PresignedUrl presignedPlayerDownload(String key, Duration validity) {
        return presign(playerPresigner, key, validity);
    }

    private PresignedUrl presign(S3Presigner signer, String key, Duration validity) {
        var request = signer.presignGetObject(p -> p
                .signatureDuration(validity)
                .getObjectRequest(g -> g.bucket(bucket).key(key)));
        return new PresignedUrl(URI.create(request.url().toString()), Instant.now().plus(validity));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
