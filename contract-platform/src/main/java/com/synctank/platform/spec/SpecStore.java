package com.synctank.platform.spec;

import com.synctank.platform.config.S3Props;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

@Service
public class SpecStore {

    private final S3Client s3;
    private final String bucket;

    public SpecStore(S3Client s3, S3Props props) {
        this.s3 = s3;
        this.bucket = props.bucket();
    }

    public void putSpec(String repo, String commit, String specJson) {
        s3.putObject(b -> b.bucket(bucket).key(specKey(repo, commit)),
                RequestBody.fromString(specJson));
    }

    public void setBaseline(String repo, String commit) {
        s3.putObject(b -> b.bucket(bucket).key(baselineKey(repo)),
                RequestBody.fromString(commit.trim()));
    }

    public String getBaselineSpec(String repo) {
        String commit = s3.getObjectAsBytes(b -> b.bucket(bucket).key(baselineKey(repo)))
                .asUtf8String().trim();
        return s3.getObjectAsBytes(b -> b.bucket(bucket).key(specKey(repo, commit)))
                .asUtf8String();
    }

    private String specKey(String repo, String commit) {
        return "specs/%s/%s.json".formatted(repo, commit);
    }

    private String baselineKey(String repo) {
        return "specs/%s/BASELINE".formatted(repo);
    }
}