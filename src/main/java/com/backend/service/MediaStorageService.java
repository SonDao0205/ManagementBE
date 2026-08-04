package com.backend.service;

public interface MediaStorageService {

    StoredMedia upload(byte[] content, String mediaType, String folder, String publicId);

    void delete(String storageKey, String mediaType);

    record StoredMedia(String storageKey, String publicUrl) {
    }
}
