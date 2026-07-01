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
package io.agentscope.extensions.oss.aliyun;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import io.agentscope.extensions.oss.base.OssAdapter;
import io.agentscope.extensions.oss.base.OssListPage;
import io.agentscope.extensions.oss.base.OssSummary;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** {@link OssAdapter} backed by the Alibaba Cloud {@code aliyun-sdk-oss} SDK. */
public class AliyunOssAdapter implements OssAdapter {

    private final OSS ossClient;
    private final String bucketName;

    public AliyunOssAdapter(OSS ossClient, String bucketName) {
        this.ossClient = Objects.requireNonNull(ossClient, "ossClient must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
    }

    public OSS ossClient() {
        return ossClient;
    }

    public String bucketName() {
        return bucketName;
    }

    @Override
    public boolean exists(String key) {
        return ossClient.doesObjectExist(bucketName, key);
    }

    @Override
    public void putBytes(String key, byte[] data) {
        ossClient.putObject(bucketName, key, new ByteArrayInputStream(data));
    }

    @Override
    public byte[] getBytes(String key) {
        if (!ossClient.doesObjectExist(bucketName, key)) {
            return null;
        }
        try (OSSObject obj = ossClient.getObject(bucketName, key);
                InputStream is = obj.getObjectContent()) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read OSS object: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key) throws FileNotFoundException {
        if (!ossClient.doesObjectExist(bucketName, key)) {
            throw new FileNotFoundException("Object not found in OSS: " + key);
        }
        return ossClient.getObject(bucketName, key).getObjectContent();
    }

    @Override
    public void delete(String key) {
        ossClient.deleteObject(bucketName, key);
    }

    @Override
    public OssListPage list(String prefix, String continuationToken, int maxKeys) {
        ListObjectsV2Request request = new ListObjectsV2Request(bucketName);
        request.setPrefix(prefix);
        request.setMaxKeys(maxKeys);
        if (continuationToken != null) {
            request.setContinuationToken(continuationToken);
        }
        ListObjectsV2Result result = ossClient.listObjectsV2(request);
        List<OssSummary> objects = new ArrayList<>();
        if (result.getObjectSummaries() != null) {
            for (OSSObjectSummary summary : result.getObjectSummaries()) {
                objects.add(new OssSummary(summary.getKey()));
            }
        }
        String next = result.isTruncated() ? result.getNextContinuationToken() : null;
        return new OssListPage(objects, next);
    }

    @Override
    public void deleteBatch(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        ossClient.deleteObjects(
                new DeleteObjectsRequest(bucketName).withKeys(keys).withQuiet(true));
    }

    @Override
    public void close() {
        ossClient.shutdown();
    }
}
