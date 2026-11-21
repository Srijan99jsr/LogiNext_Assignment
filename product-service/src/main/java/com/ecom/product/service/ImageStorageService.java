package com.ecom.product.service;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Product images live in a private S3 bucket; clients get short-lived presigned URLs. */
@Service
public class ImageStorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public ImageStorageService(S3Client s3, S3Presigner presigner, @Value("${app.s3-bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public String upload(Long productId, MultipartFile file) throws IOException {
        String safeName = file.getOriginalFilename() == null ? "image"
                : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_");
        String key = "products/" + productId + "/" + UUID.randomUUID() + "-" + safeName;
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key).contentType(file.getContentType()).build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        return key;
    }

    public String presignedUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build()).url().toString();
    }
}
