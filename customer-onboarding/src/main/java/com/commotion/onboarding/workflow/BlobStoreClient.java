package com.commotion.onboarding.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Thin facade over the platform's `blob-store-utils` (AWS S3 / Azure Blob /
 * GCP Cloud Storage). Only the S3 backend is wired in this skeleton.
 */
@Component
@RequiredArgsConstructor
public class BlobStoreClient {

    private final S3Client s3;

    public byte[] fetch(String bucket, String key) {
        ResponseBytes<?> resp = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
        return resp.asByteArray();
    }
}
