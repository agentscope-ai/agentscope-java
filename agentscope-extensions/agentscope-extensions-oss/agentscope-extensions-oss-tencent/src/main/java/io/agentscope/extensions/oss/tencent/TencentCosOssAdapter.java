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
package io.agentscope.extensions.oss.tencent;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.DeleteObjectsRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import io.agentscope.extensions.oss.base.OssAdapter;
import io.agentscope.extensions.oss.base.OssListObjectPage;
import io.agentscope.extensions.oss.base.OssObjectSummary;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** {@link OssAdapter} backed by the Tencent Cloud {@code cos_api} SDK. */
public class TencentCosOssAdapter implements OssAdapter {

    private final COSClient cosClient;
    private final String bucketName;

    public TencentCosOssAdapter(COSClient cosClient, String bucketName) {
        this.cosClient = Objects.requireNonNull(cosClient, "cosClient must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
    }

    public COSClient cosClient() {
        return cosClient;
    }

    public String bucketName() {
        return bucketName;
    }

    @Override
    public boolean exists(String key) {
        try {
            cosClient.getObjectMetadata(bucketName, key);
            return true;
        } catch (CosServiceException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw new RuntimeException("Failed to check COS object existence: " + key, e);
        }
    }

    @Override
    public void putBytes(String key, byte[] data) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        cosClient.putObject(
                new PutObjectRequest(bucketName, key, new ByteArrayInputStream(data), metadata));
    }

    @Override
    public void putStream(String key, InputStream data) throws Exception {
        // Tencent COS SDK's InputStream-based putObject requires a known Content-Length;
        ObjectMetadata omt = new ObjectMetadata();
        omt.setContentLength(data.available());
        omt.setContentType("application/octet-stream");
        omt.setCacheControl("no-cache");
        cosClient.putObject(new PutObjectRequest(bucketName, key, data, omt));
    }

    @Override
    public byte[] getBytes(String key) {
        if (!exists(key)) {
            return null;
        }
        try (COSObject obj = cosClient.getObject(new GetObjectRequest(bucketName, key));
                InputStream is = obj.getObjectContent()) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read COS object: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key) throws FileNotFoundException {
        if (!exists(key)) {
            throw new FileNotFoundException("Object not found in COS: " + key);
        }
        return cosClient.getObject(new GetObjectRequest(bucketName, key)).getObjectContent();
    }

    @Override
    public void delete(String key) {
        cosClient.deleteObject(bucketName, key);
    }

    @Override
    public OssListObjectPage list(String prefix, String continuationToken, int maxKeys) {
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(bucketName);
        request.setPrefix(prefix);
        request.setMaxKeys(maxKeys);
        if (continuationToken != null) {
            request.setMarker(continuationToken);
        }
        ObjectListing result = cosClient.listObjects(request);
        List<OssObjectSummary> objects = new ArrayList<>();
        if (result.getObjectSummaries() != null) {
            for (COSObjectSummary summary : result.getObjectSummaries()) {
                objects.add(new OssObjectSummary(summary.getKey()));
            }
        }
        String next = result.isTruncated() ? result.getNextMarker() : null;
        return new OssListObjectPage(objects, next);
    }

    @Override
    public void deleteBatch(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        DeleteObjectsRequest req = new DeleteObjectsRequest(bucketName);
        req.setKeys(
                keys.stream()
                        .map(DeleteObjectsRequest.KeyVersion::new)
                        .collect(Collectors.toList()));
        cosClient.deleteObjects(req);
    }

    @Override
    public void close() {
        cosClient.shutdown();
    }
}
