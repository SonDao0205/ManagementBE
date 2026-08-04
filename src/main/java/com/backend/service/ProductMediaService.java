package com.backend.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.backend.dto.ProductMediaOrderRequest;
import com.backend.dto.ProductMediaResponse;
import com.backend.entity.ProductMediaEntity;
import com.backend.repository.ProductMediaRepository;
import com.backend.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductMediaService {

    private static final int MAX_MEDIA_PER_PRODUCT = 20;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    private static final long MAX_REQUEST_BYTES = 200L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime");

    private final ProductRepository productRepository;
    private final ProductMediaRepository mediaRepository;
    private final MediaStorageService storageService;

    public ProductMediaService(
            ProductRepository productRepository,
            ProductMediaRepository mediaRepository,
            MediaStorageService storageService
    ) {
        this.productRepository = productRepository;
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
    }

    @Transactional
    public List<ProductMediaResponse> upload(
            String tenantId,
            String productId,
            List<MultipartFile> files
    ) {
        requireProduct(tenantId, productId);
        if (files == null || files.isEmpty()) {
            throw badRequest("MEDIA_REQUIRED", "Vui lòng chọn ít nhất một ảnh hoặc video.");
        }

        List<ProductMediaEntity> existing = activeMedia(tenantId, productId);
        if (existing.size() + files.size() > MAX_MEDIA_PER_PRODUCT) {
            throw badRequest("TOO_MANY_MEDIA", "Mỗi sản phẩm được lưu tối đa 20 ảnh và video.");
        }
        long requestBytes = files.stream()
                .filter(java.util.Objects::nonNull)
                .mapToLong(MultipartFile::getSize)
                .sum();
        if (requestBytes > MAX_REQUEST_BYTES) {
            throw new ApiException(
                    HttpStatus.valueOf(413),
                    "MEDIA_REQUEST_TOO_LARGE",
                    "Mỗi lượt tải lên được tối đa 200MB.");
        }

        List<ValidatedMedia> validatedFiles = files.stream()
                .map(file -> new ValidatedMedia(
                        readAndValidate(file),
                        resolveMediaType(file.getContentType())))
                .toList();

        int nextSortOrder = existing.stream()
                .map(ProductMediaEntity::getSortOrder)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
        boolean hasPrimary = existing.stream().anyMatch(ProductMediaEntity::isPrimary);
        List<ProductMediaEntity> uploadedEntities = new ArrayList<>();
        List<StoredMediaReference> uploadedStorage = new ArrayList<>();

        try {
            for (ValidatedMedia file : validatedFiles) {
                String publicId = UUID.randomUUID().toString().replace("-", "");
                var uploaded = storageService.upload(
                        file.content(),
                        file.mediaType(),
                        "tenants/" + tenantId + "/products/" + productId,
                        publicId);
                uploadedStorage.add(new StoredMediaReference(
                        uploaded.storageKey(), file.mediaType()));

                ProductMediaEntity media = new ProductMediaEntity();
                media.setId(UUID.randomUUID().toString());
                media.setTenantId(tenantId);
                media.setProductId(productId);
                media.setMediaType(file.mediaType());
                media.setStorageKey(uploaded.storageKey());
                media.setPublicUrl(uploaded.publicUrl());
                media.setChecksumSha256(sha256(file.content()));
                media.setSortOrder(nextSortOrder++);
                media.setPrimary(!hasPrimary);
                media.setCreatedAt(Instant.now());
                uploadedEntities.add(mediaRepository.save(media));
                hasPrimary = true;
            }
            mediaRepository.flush();
        } catch (RuntimeException exception) {
            compensateUploads(uploadedStorage);
            throw exception;
        }
        return uploadedEntities.stream().map(ProductMediaService::toResponse).toList();
    }

    @Transactional
    public List<ProductMediaResponse> reorder(
            String tenantId,
            String productId,
            ProductMediaOrderRequest request
    ) {
        requireProduct(tenantId, productId);
        List<ProductMediaEntity> media = activeMedia(tenantId, productId);
        if (request.items().size() != media.size()) {
            throw badRequest(
                    "INCOMPLETE_MEDIA_ORDER",
                    "Danh sách sắp xếp phải chứa toàn bộ media hiện có của sản phẩm.");
        }

        Set<String> requestedIds = new HashSet<>();
        Set<Integer> requestedSortOrders = new HashSet<>();
        long primaryCount = request.items().stream().filter(ProductMediaOrderRequest.Item::primary).count();
        if (primaryCount != 1) {
            throw badRequest("INVALID_PRIMARY_MEDIA", "Sản phẩm phải có đúng một media chính.");
        }
        request.items().forEach(item -> {
            if (!requestedIds.add(item.mediaId())) {
                throw badRequest("DUPLICATE_MEDIA", "Danh sách media có phần tử bị trùng.");
            }
            if (!requestedSortOrders.add(item.sortOrder())) {
                throw badRequest("DUPLICATE_MEDIA_ORDER", "Thứ tự media có giá trị bị trùng.");
            }
        });
        Set<Integer> expectedSortOrders = java.util.stream.IntStream.range(0, media.size())
                .boxed()
                .collect(java.util.stream.Collectors.toSet());
        if (!requestedSortOrders.equals(expectedSortOrders)) {
            throw badRequest(
                    "INVALID_MEDIA_ORDER",
                    "Thứ tự media phải liên tục từ 0 đến " + (media.size() - 1) + ".");
        }
        if (!requestedIds.equals(media.stream().map(ProductMediaEntity::getId).collect(java.util.stream.Collectors.toSet()))) {
            throw badRequest("INVALID_MEDIA", "Có media không thuộc sản phẩm này.");
        }

        var itemById = request.items().stream().collect(java.util.stream.Collectors.toMap(
                ProductMediaOrderRequest.Item::mediaId,
                item -> item));
        media.forEach(item -> {
            var order = itemById.get(item.getId());
            item.setSortOrder(order.sortOrder());
            item.setPrimary(order.primary());
        });
        mediaRepository.saveAll(media);
        return media.stream()
                .sorted(Comparator.comparing(ProductMediaEntity::getSortOrder))
                .map(ProductMediaService::toResponse)
                .toList();
    }

    @Transactional
    public void delete(String tenantId, String productId, String mediaId) {
        requireProduct(tenantId, productId);
        ProductMediaEntity media = mediaRepository
                .findByIdAndProductIdAndTenantIdAndDeletedAtIsNull(mediaId, productId, tenantId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Không tìm thấy media của sản phẩm."));
        storageService.delete(media.getStorageKey(), media.getMediaType());
        media.setDeletedAt(Instant.now());
        media.setPrimary(false);
        mediaRepository.save(media);

        List<ProductMediaEntity> remaining = activeMedia(tenantId, productId).stream()
                .filter(item -> !item.getId().equals(mediaId))
                .toList();
        if (!remaining.isEmpty() && remaining.stream().noneMatch(ProductMediaEntity::isPrimary)) {
            ProductMediaEntity first = remaining.get(0);
            first.setPrimary(true);
            mediaRepository.save(first);
        }
    }

    private void requireProduct(String tenantId, String productId) {
        productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm."));
    }

    private List<ProductMediaEntity> activeMedia(String tenantId, String productId) {
        return mediaRepository
                .findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                        productId,
                        tenantId);
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("EMPTY_MEDIA", "Ảnh hoặc video được chọn đang rỗng.");
        }
        String mediaType = resolveMediaType(file.getContentType());
        long maxBytes = "VIDEO".equals(mediaType) ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
        if (file.getSize() > maxBytes) {
            throw badRequest(
                    "MEDIA_TOO_LARGE",
                    "Ảnh tối đa 10MB và video tối đa 100MB.");
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException exception) {
            throw badRequest("MEDIA_READ_FAILED", "Không thể đọc ảnh hoặc video được chọn.");
        }
    }

    private String resolveMediaType(String contentType) {
        if (contentType != null && IMAGE_TYPES.contains(contentType.toLowerCase())) {
            return "IMAGE";
        }
        if (contentType != null && VIDEO_TYPES.contains(contentType.toLowerCase())) {
            return "VIDEO";
        }
        throw badRequest(
                "UNSUPPORTED_MEDIA_TYPE",
                "Chỉ hỗ trợ JPG, PNG, WEBP, GIF, MP4, WEBM hoặc MOV.");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void compensateUploads(List<StoredMediaReference> uploadedStorage) {
        for (StoredMediaReference uploaded : uploadedStorage) {
            try {
                storageService.delete(uploaded.storageKey(), uploaded.mediaType());
            } catch (RuntimeException ignored) {
                // Preserve the original upload/database error; provider cleanup can be retried manually.
            }
        }
    }

    private record ValidatedMedia(byte[] content, String mediaType) {
    }

    private record StoredMediaReference(String storageKey, String mediaType) {
    }

    public static ProductMediaResponse toResponse(ProductMediaEntity media) {
        return new ProductMediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getStorageKey(),
                media.getPublicUrl(),
                media.getSortOrder(),
                media.isPrimary(),
                media.getProductVariantId(),
                media.getCreatedAt());
    }

    private static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
