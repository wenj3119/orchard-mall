package com.orchard.mall;

import java.io.InputStream;

public interface ObjectStorage {
    void put(String key, InputStream data, long size, String contentType) throws Exception;
    StoredObject get(String key) throws Exception;
    record StoredObject(InputStream stream, String contentType) {}
}
