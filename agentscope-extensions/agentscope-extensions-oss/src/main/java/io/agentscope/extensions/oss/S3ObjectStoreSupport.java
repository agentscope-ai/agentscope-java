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
package io.agentscope.extensions.oss;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

final class S3ObjectStoreSupport {

    private S3ObjectStoreSupport() {}

    static S3Client buildClient(URI endpoint, String accessKeyId, String accessKeySecret) {
        return buildClient(endpoint, Region.US_EAST_1, accessKeyId, accessKeySecret);
    }

    static S3Client buildClient(
            URI endpoint, Region region, String accessKeyId, String accessKeySecret) {
        return S3Client.builder()
                .region(region)
                .endpointOverride(endpoint)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, accessKeySecret)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    static String normalizePrefix(String prefix, String defaultPrefix) {
        if (prefix == null || prefix.isBlank()) {
            return defaultPrefix;
        }
        String p = prefix.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p.isEmpty() ? defaultPrefix : p;
    }

    static void putString(S3Client s3Client, String bucketName, String objectKey, String content) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(objectKey).build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    static String getString(S3Client s3Client, String bucketName, String objectKey) {
        try {
            ResponseBytes<GetObjectResponse> bytes =
                    s3Client.getObjectAsBytes(
                            GetObjectRequest.builder().bucket(bucketName).key(objectKey).build());
            return bytes.asUtf8String();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                return null;
            }
            throw e;
        }
    }

    static boolean hasObjectsWithPrefix(S3Client s3Client, String bucketName, String prefix) {
        ListObjectsV2Response response =
                s3Client.listObjectsV2(
                        ListObjectsV2Request.builder()
                                .bucket(bucketName)
                                .prefix(prefix)
                                .maxKeys(1)
                                .build());
        return response.contents() != null && !response.contents().isEmpty();
    }

    static List<String> listAllKeys(S3Client s3Client, String bucketName, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request =
                    ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).maxKeys(1000);
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3Client.listObjectsV2(request.build());
            if (response.contents() != null) {
                for (S3Object object : response.contents()) {
                    keys.add(object.key());
                }
            }
            continuationToken =
                    Boolean.TRUE.equals(response.isTruncated())
                            ? response.nextContinuationToken()
                            : null;
        } while (continuationToken != null);
        return keys;
    }

    static void deleteKeys(S3Client s3Client, String bucketName, List<String> keys) {
        for (int i = 0; i < keys.size(); i += 1000) {
            List<ObjectIdentifier> batch =
                    keys.subList(i, Math.min(i + 1000, keys.size())).stream()
                            .map(key -> ObjectIdentifier.builder().key(key).build())
                            .toList();
            s3Client.deleteObjects(
                    DeleteObjectsRequest.builder()
                            .bucket(bucketName)
                            .delete(Delete.builder().quiet(true).objects(batch).build())
                            .build());
        }
    }

    static boolean isNotFound(S3Exception e) {
        if (e.statusCode() == 404) {
            return true;
        }
        if (e.awsErrorDetails() == null) {
            return false;
        }
        String errorCode = e.awsErrorDetails().errorCode();
        return "NoSuchKey".equals(errorCode) || "NotFound".equals(errorCode);
    }
}
