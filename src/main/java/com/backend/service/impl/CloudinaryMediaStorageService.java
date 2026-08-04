package com.backend.service.impl;

import java.io.IOException;
import java.util.Map;

import com.backend.config.CloudinaryProperties;
import com.backend.service.ApiException;
import com.backend.service.MediaStorageService;
import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CloudinaryMediaStorageService implements MediaStorageService {

    private final Cloudinary cloudinary;
    private final CloudinaryProperties properties;

    public CloudinaryMediaStorageService(
            Cloudinary cloudinary,
            CloudinaryProperties properties
    ) {
        this.cloudinary = cloudinary;
        this.properties = properties;
    }

    @Override
    public StoredMedia upload(byte[] content, String mediaType, String folder, String publicId) {
        requireConfiguration();
        String resourceType = "VIDEO".equals(mediaType) ? "video" : "image";
        try {
            Map<?, ?> result = cloudinary.uploader().upload(content, ObjectUtils.asMap(
                    "resource_type", resourceType,
                    "asset_folder", properties.folder() + "/" + folder,
                    "public_id", publicId,
                    "overwrite", false,
                    "unique_filename", false));
            String storageKey = result.get("public_id") == null
                    ? "" : String.valueOf(result.get("public_id"));
            String publicUrl = result.get("secure_url") == null
                    ? "" : String.valueOf(result.get("secure_url"));
            if (storageKey.isBlank() || publicUrl.isBlank()) {
                throw new IllegalStateException("Cloudinary response is missing public_id or secure_url");
            }
            return new StoredMedia(
                    storageKey,
                    publicUrl);
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "CLOUDINARY_UPLOAD_FAILED",
                    "Không thể tải ảnh hoặc video lên Cloudinary. Vui lòng kiểm tra cấu hình và thử lại.");
        }
    }

    @Override
    public void delete(String storageKey, String mediaType) {
        requireConfiguration();
        String resourceType = "VIDEO".equals(mediaType) ? "video" : "image";
        try {
            cloudinary.uploader().destroy(storageKey, ObjectUtils.asMap(
                    "resource_type", resourceType,
                    "invalidate", true));
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "CLOUDINARY_DELETE_FAILED",
                    "Không thể xóa media khỏi Cloudinary. Vui lòng thử lại.");
        }
    }

    private void requireConfiguration() {
        if (!properties.configured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CLOUDINARY_NOT_CONFIGURED",
                    "Cloudinary chưa được cấu hình trên máy chủ.");
        }
    }
}
