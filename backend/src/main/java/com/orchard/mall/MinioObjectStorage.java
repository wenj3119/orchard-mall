package com.orchard.mall;

import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.InputStream;

@Service
public class MinioObjectStorage implements ObjectStorage {
    @Value("${storage.endpoint}") private String endpoint;
    @Value("${storage.access-key}") private String accessKey;
    @Value("${storage.secret-key}") private String secretKey;
    @Value("${storage.bucket}") private String bucket;
    private MinioClient client() {
        if(accessKey.isBlank() || secretKey.isBlank()) throw new IllegalStateException("MinIO credentials are required");
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).build();
    }
    @Override public void put(String key, InputStream data, long size, String contentType) throws Exception {
        var client=client();
        if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(data,size,-1).contentType(contentType).build());
    }
    @Override public StoredObject get(String key) throws Exception {
        return new StoredObject(client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()),"application/octet-stream");
    }
}
