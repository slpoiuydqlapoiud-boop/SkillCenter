package com.huawei.skillcenter.governance;

public class CollectionNotFoundException extends RuntimeException {
    public CollectionNotFoundException(String collectionId) {
        super("Collection not found: " + collectionId);
    }
}
