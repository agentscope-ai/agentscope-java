/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.oss.aws;

import io.agentscope.extensions.oss.base.OssAdapter;
import io.agentscope.extensions.oss.base.OssListPage;
import io.agentscope.extensions.oss.base.OssSummary;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** {@link OssAdapter} backed by the AWS SDK for Java v2 S3 client. */
public class AwsS3OssAdapter implements OssAdapter {

    private final S3Client s3Client;
    private final String bucketName;

    public AwsS3OssAdapter(S3Client s3Client, String bucketName) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
    }

    public S3Client s3Client() {
        return s3Client;
    }

    public String bucketName() {
        return bucketName;
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void putBytes(String key, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public byte[] getBytes(String key) {
        try (ResponseInputStream<GetObjectResponse> is =
                s3Client.getObject(
                        GetObjectRequest.builder().bucket(bucketName).key(key).build())) {
            return is.readAllBytes();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read S3 object: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key) throws FileNotFoundException {
        try {
            return s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException("Object not found in S3: " + key);
        }
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
    }

    @Override
    public OssListPage list(String prefix, String continuationToken, int maxKeys) {
        ListObjectsV2Request.Builder request =
                ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).maxKeys(maxKeys);
        if (continuationToken != null) {
            request.continuationToken(continuationToken);
        }
        ListObjectsV2Response response = s3Client.listObjectsV2(request.build());
        List<OssSummary> objects = new ArrayList<>();
        if (response.contents() != null) {
            for (software.amazon.awssdk.services.s3.model.S3Object obj : response.contents()) {
                objects.add(new OssSummary(obj.key()));
            }
        }
        String next =
                Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken()
                        : null;
        return new OssListPage(objects, next);
    }

    @Override
    public void deleteBatch(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<ObjectIdentifier> ids =
                keys.stream()
                        .map(k -> ObjectIdentifier.builder().key(k).build())
                        .collect(Collectors.toList());
        s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(ids).quiet(true).build())
                        .build());
    }

    @Override
    public void close() {
        s3Client.close();
    }
}
