package com.backend.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.CreateCustomerRequest;
import com.backend.dto.CustomerDetailResponse;
import com.backend.dto.CustomerInteractionResponse;
import com.backend.dto.CustomerMetricsDto;
import com.backend.dto.CustomerResponse;
import com.backend.dto.LinkedChannelDto;
import com.backend.dto.MergeCustomersRequest;
import com.backend.dto.UpdateCustomerRequest;
import com.backend.entity.CustomerEntity;
import com.backend.entity.CustomerIdentityLinkEntity;
import com.backend.entity.CustomerBehaviorEventEntity;
import com.backend.entity.TenantEntity;
import com.backend.entity.TenantUserEntity;
import com.backend.repository.CustomerBehaviorEventRepository;
import com.backend.repository.CustomerIdentityLinkRepository;
import com.backend.repository.CustomerRepository;
import com.backend.repository.TenantRepository;
import com.backend.repository.TenantUserRepository;
import com.backend.security.CustomerEncryptionService;
import com.backend.service.AuthenticationException;
import com.backend.service.CustomerService;

import jakarta.persistence.EntityManager;

@Service
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final CustomerBehaviorEventRepository customerBehaviorEventRepository;
    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final CustomerEncryptionService encryptionService;
    private final EntityManager entityManager;
    private final Random random = new Random();

    public CustomerServiceImpl(
            CustomerRepository customerRepository,
            CustomerIdentityLinkRepository customerIdentityLinkRepository,
            CustomerBehaviorEventRepository customerBehaviorEventRepository,
            TenantRepository tenantRepository,
            TenantUserRepository tenantUserRepository,
            CustomerEncryptionService encryptionService,
            EntityManager entityManager) {
        this.customerRepository = customerRepository;
        this.customerIdentityLinkRepository = customerIdentityLinkRepository;
        this.customerBehaviorEventRepository = customerBehaviorEventRepository;
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.encryptionService = encryptionService;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerResponse> getCustomerList(String tenantId, String search, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        String searchVal = (search == null || search.isBlank()) ? null : search.trim();
        String statusVal = (status == null || status.isBlank()) ? null : status.trim();

        String phoneHmac = null;
        String emailHmac = null;
        if (searchVal != null) {
            phoneHmac = encryptionService.generateLookupHmac(searchVal);
            emailHmac = encryptionService.generateLookupHmac(searchVal);
        }

        Page<CustomerEntity> entities = customerRepository.searchCustomers(
                tenantId, searchVal, phoneHmac, emailHmac, statusVal, pageable);

        return entities.map(entity -> toCustomerResponse(entity, tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDetailResponse getCustomerDetail(String tenantId, String id) {
        CustomerEntity customer = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng."));

        String phoneDecrypted = encryptionService.decrypt(customer.getPhoneNormalizedEncrypted());
        String emailDecrypted = encryptionService.decrypt(customer.getEmailNormalizedEncrypted());

        List<CustomerIdentityLinkEntity> links = customerIdentityLinkRepository.findByCustomerIdAndTenantId(id, tenantId);
        List<LinkedChannelDto> channelDtos = links.stream()
                .map(link -> new LinkedChannelDto(
                        link.getId(),
                        link.getMarketplaceCustomerId(),
                        mapMarketplaceIdToName(link.getMarketplaceCustomer().getMarketplaceAccount().getMarketplaceId()),
                        link.getMarketplaceCustomer().getMarketplaceAccount().getExternalShopName(),
                        link.getMarketplaceCustomer().getDisplayName(),
                        link.getMarketplaceCustomer().getAvatarUrl(),
                        link.getMarketplaceCustomer().getPhoneMasked(),
                        link.getMarketplaceCustomer().getEmailMasked(),
                        link.getVerificationStatus(),
                        link.getCreatedAt()
                ))
                .toList();

        // Calculate order metrics natively
        List<String> mcIds = links.stream()
                .map(CustomerIdentityLinkEntity::getMarketplaceCustomerId)
                .toList();

        long totalOrders = 0;
        double totalSpend = 0.0;
        String lastChannelSeen = "Chưa có";

        if (!mcIds.isEmpty()) {
            Object[] metricsResult = (Object[]) entityManager.createNativeQuery(
                    "SELECT COUNT(id), COALESCE(SUM(total_amount), 0) FROM orders WHERE tenant_id = :tenantId AND marketplace_customer_id IN :mcIds")
                    .setParameter("tenantId", tenantId)
                    .setParameter("mcIds", mcIds)
                    .getSingleResult();
            totalOrders = ((Number) metricsResult[0]).longValue();
            totalSpend = ((Number) metricsResult[1]).doubleValue();

            List<?> channelResult = entityManager.createNativeQuery(
                    "SELECT ma.marketplace_id FROM orders o JOIN marketplace_accounts ma ON ma.id = o.marketplace_account_id WHERE o.tenant_id = :tenantId AND o.marketplace_customer_id IN :mcIds ORDER BY o.created_at DESC LIMIT 1")
                    .setParameter("tenantId", tenantId)
                    .setParameter("mcIds", mcIds)
                    .getResultList();
            if (!channelResult.isEmpty()) {
                lastChannelSeen = mapMarketplaceIdToName((String) channelResult.get(0));
            }
        }

        CustomerMetricsDto metrics = new CustomerMetricsDto(totalOrders, totalSpend, lastChannelSeen);

        return new CustomerDetailResponse(
                customer.getId(),
                customer.getCustomerCode(),
                customer.getDisplayName(),
                phoneDecrypted,
                emailDecrypted,
                customer.getIdentityStatus(),
                customer.getCreatedAt(),
                customer.getUpdatedAt(),
                customer.getMergedIntoId(),
                channelDtos,
                metrics
        );
    }

    @Override
    @Transactional
    public CustomerResponse createCustomer(String tenantId, CreateCustomerRequest request) {
        String displayName = request.displayName().trim();
        String phone = (request.phoneNumber() == null || request.phoneNumber().isBlank()) ? null : request.phoneNumber().trim();
        String email = (request.email() == null || request.email().isBlank()) ? null : request.email().trim();

        String phoneHmac = encryptionService.generateLookupHmac(phone);
        String emailHmac = encryptionService.generateLookupHmac(email);

        // Enforce hard uniqueness constraints on email/phone within the tenant
        if (phoneHmac != null && customerRepository.findByTenantIdAndPhoneLookupHmacAndDeletedAtIsNull(tenantId, phoneHmac).isPresent()) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "PHONE_ALREADY_EXISTS", "Số điện thoại đã tồn tại ở khách hàng khác.");
        }
        if (emailHmac != null && customerRepository.findByTenantIdAndEmailLookupHmacAndDeletedAtIsNull(tenantId, emailHmac).isPresent()) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_EXISTS", "Email đã tồn tại ở khách hàng khác.");
        }

        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Không tìm thấy Shop."));

        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID().toString());
        customer.setTenant(tenant);
        customer.setCustomerCode("CUST-" + generateRandomCode());
        customer.setDisplayName(displayName);
        customer.setPhoneNormalizedEncrypted(encryptionService.encrypt(phone));
        customer.setEmailNormalizedEncrypted(encryptionService.encrypt(email));
        customer.setPhoneLookupHmac(phoneHmac);
        customer.setEmailLookupHmac(emailHmac);
        customer.setPiiKeyVersion(encryptionService.keyVersion());
        customer.setIdentityStatus("VERIFIED");
        customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());

        CustomerEntity saved = customerRepository.save(customer);

        return toCustomerResponse(saved, tenantId);
    }

    @Override
    @Transactional
    public CustomerResponse updateCustomer(String tenantId, String id, UpdateCustomerRequest request) {
        CustomerEntity customer = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng."));

        if ("MERGED".equals(customer.getIdentityStatus())) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "CUSTOMER_MERGED", "Không thể cập nhật hồ sơ khách hàng đã bị gộp.");
        }

        String displayName = request.displayName().trim();
        String phone = (request.phoneNumber() == null || request.phoneNumber().isBlank()) ? null : request.phoneNumber().trim();
        String email = (request.email() == null || request.email().isBlank()) ? null : request.email().trim();

        String phoneHmac = encryptionService.generateLookupHmac(phone);
        String emailHmac = encryptionService.generateLookupHmac(email);

        if (phoneHmac != null) {
            customerRepository.findByTenantIdAndPhoneLookupHmacAndDeletedAtIsNull(tenantId, phoneHmac)
                    .filter(c -> !c.getId().equals(id))
                    .ifPresent(c -> {
                        throw new AuthenticationException(HttpStatus.BAD_REQUEST, "PHONE_ALREADY_EXISTS", "Số điện thoại đã tồn tại ở khách hàng khác.");
                    });
        }
        if (emailHmac != null) {
            customerRepository.findByTenantIdAndEmailLookupHmacAndDeletedAtIsNull(tenantId, emailHmac)
                    .filter(c -> !c.getId().equals(id))
                    .ifPresent(c -> {
                        throw new AuthenticationException(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_EXISTS", "Email đã tồn tại ở khách hàng khác.");
                    });
        }

        customer.setDisplayName(displayName);
        customer.setPhoneNormalizedEncrypted(encryptionService.encrypt(phone));
        customer.setEmailNormalizedEncrypted(encryptionService.encrypt(email));
        customer.setPhoneLookupHmac(phoneHmac);
        customer.setEmailLookupHmac(emailHmac);
        customer.setUpdatedAt(Instant.now());

        CustomerEntity saved = customerRepository.save(customer);

        return toCustomerResponse(saved, tenantId);
    }

    @Override
    @Transactional
    public void deleteCustomer(String tenantId, String id) {
        CustomerEntity customer = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng."));

        customer.setDeletedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());
        customerRepository.save(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponse> getPotentialDuplicates(String tenantId, String customerId) {
        CustomerEntity customer = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng."));

        if ("MERGED".equals(customer.getIdentityStatus())) {
            return Collections.emptyList();
        }

        List<CustomerEntity> duplicates = customerRepository.findPotentialDuplicates(
                tenantId, customer.getDisplayName(), customerId);

        return duplicates.stream()
                .map(entity -> toCustomerResponse(entity, tenantId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerInteractionResponse> getInteractions(String tenantId, String customerId) {
        CustomerEntity customer = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng."));

        List<CustomerIdentityLinkEntity> links = customerIdentityLinkRepository.findByCustomerIdAndTenantId(customerId, tenantId);
        if (links.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> mcIds = links.stream()
                .map(CustomerIdentityLinkEntity::getMarketplaceCustomerId)
                .toList();

        List<CustomerBehaviorEventEntity> events = customerBehaviorEventRepository.findInteractions(tenantId, mcIds);

        return events.stream()
                .map(event -> new CustomerInteractionResponse(
                        event.getEventId(),
                        mapEventName(event.getEventName()),
                        event.getMarketplaceCode(),
                        event.getMarketplaceAccount().getExternalShopName(),
                        event.getScreen(),
                        event.getEntityType(),
                        event.getEntityExternalId(),
                        event.getPropertiesJson(),
                        event.getOccurredAt()
                ))
                .toList();
    }

    @Override
    @Transactional
    public void mergeCustomers(String tenantId, MergeCustomersRequest request, String userId) {
        String sourceId = request.sourceCustomerId();
        String targetId = request.targetCustomerId();

        if (sourceId.equals(targetId)) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "INVALID_MERGE", "Không thể gộp một khách hàng vào chính họ.");
        }

        CustomerEntity source = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(sourceId, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "SOURCE_CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng nguồn."));

        CustomerEntity target = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(targetId, tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "TARGET_CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng đích."));

        if ("MERGED".equals(source.getIdentityStatus())) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "SOURCE_ALREADY_MERGED", "Khách hàng nguồn đã được gộp trước đó.");
        }
        if ("MERGED".equals(target.getIdentityStatus())) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "TARGET_ALREADY_MERGED", "Khách hàng đích hiện đã bị gộp.");
        }

        TenantUserEntity verifier = tenantUserRepository.findById(userId)
                .orElse(null);

        // Perform merge fields consolidation
        String mergedDisplayName = request.selectedDisplayName() != null ? request.selectedDisplayName().trim() : target.getDisplayName();
        String mergedPhone = (request.selectedPhone() == null || request.selectedPhone().isBlank()) ? null : request.selectedPhone().trim();
        String mergedEmail = (request.selectedEmail() == null || request.selectedEmail().isBlank()) ? null : request.selectedEmail().trim();

        String mergedPhoneHmac = encryptionService.generateLookupHmac(mergedPhone);
        String mergedEmailHmac = encryptionService.generateLookupHmac(mergedEmail);

        // Clear source constraints so they don't block the target or future creates
        source.setPhoneLookupHmac(null);
        source.setEmailLookupHmac(null);
        source.setPhoneNormalizedEncrypted(null);
        source.setEmailNormalizedEncrypted(null);
        source.setIdentityStatus("MERGED");
        source.setMergedInto(target);
        source.setUpdatedAt(Instant.now());

        // Save source first to free up the HMACs in the DB if they conflict
        customerRepository.saveAndFlush(source);

        // Update target customer
        target.setDisplayName(mergedDisplayName);
        target.setPhoneNormalizedEncrypted(encryptionService.encrypt(mergedPhone));
        target.setEmailNormalizedEncrypted(encryptionService.encrypt(mergedEmail));
        target.setPhoneLookupHmac(mergedPhoneHmac);
        target.setEmailLookupHmac(mergedEmailHmac);
        target.setIdentityStatus("VERIFIED");
        target.setUpdatedAt(Instant.now());

        customerRepository.save(target);

        // Transfer identity links
        List<CustomerIdentityLinkEntity> sourceLinks = customerIdentityLinkRepository.findByCustomerId(sourceId);
        for (CustomerIdentityLinkEntity link : sourceLinks) {
            // Check if target already has link for the same marketplace customer
            boolean targetHasLink = customerIdentityLinkRepository.findByMarketplaceCustomerId(link.getMarketplaceCustomerId())
                    .filter(l -> l.getCustomerId().equals(targetId))
                    .isPresent();

            if (targetHasLink) {
                // Delete duplicate link
                customerIdentityLinkRepository.delete(link);
            } else {
                // Re-route link to target
                link.setCustomer(target);
                link.setVerificationStatus("VERIFIED");
                link.setVerifiedByUser(verifier);
                link.setVerifiedAt(Instant.now());
                customerIdentityLinkRepository.save(link);
            }
        }
    }

    private CustomerResponse toCustomerResponse(CustomerEntity entity, String tenantId) {
        String decryptedPhone = encryptionService.decrypt(entity.getPhoneNormalizedEncrypted());
        String decryptedEmail = encryptionService.decrypt(entity.getEmailNormalizedEncrypted());

        // Check if there are other active customer records with the same name
        List<CustomerEntity> duplicates = customerRepository.findPotentialDuplicates(
                tenantId, entity.getDisplayName(), entity.getId());
        boolean hasDuplicates = !duplicates.isEmpty();

        long totalOrders = 0;
        double totalSpend = 0.0;
        List<CustomerIdentityLinkEntity> links = customerIdentityLinkRepository.findByCustomerIdAndTenantId(entity.getId(), tenantId);
        List<String> mcIds = links.stream()
                .map(CustomerIdentityLinkEntity::getMarketplaceCustomerId)
                .toList();

        if (!mcIds.isEmpty()) {
            Object[] metricsResult = (Object[]) entityManager.createNativeQuery(
                    "SELECT COUNT(id), COALESCE(SUM(total_amount), 0) FROM orders WHERE tenant_id = :tenantId AND marketplace_customer_id IN :mcIds")
                    .setParameter("tenantId", tenantId)
                    .setParameter("mcIds", mcIds)
                    .getSingleResult();
            totalOrders = ((Number) metricsResult[0]).longValue();
            totalSpend = ((Number) metricsResult[1]).doubleValue();
        }

        return new CustomerResponse(
                entity.getId(),
                entity.getCustomerCode(),
                entity.getDisplayName(),
                decryptedPhone,
                decryptedEmail,
                entity.getIdentityStatus(),
                entity.getCreatedAt(),
                hasDuplicates,
                totalOrders,
                totalSpend
        );
    }

    private String generateRandomCode() {
        int val = random.nextInt(900000) + 100000;
        return String.valueOf(val);
    }

    private String mapMarketplaceIdToName(String marketplaceId) {
        if (marketplaceId == null) return "Cửa hàng";
        return switch (marketplaceId.toUpperCase()) {
            case "TIKTOK_SHOP", "TIKTOKSHOP" -> "TikTok Shop";
            case "LAZADA" -> "Lazada";
            case "SHOPEE" -> "Shopee";
            default -> "Web Store";
        };
    }

    private String mapEventName(String eventName) {
        if (eventName == null) return "Hành động khác";
        return switch (eventName.toUpperCase()) {
            case "ADD_TO_CART", "ADD" -> "Thêm sản phẩm vào giỏ hàng";
            case "VIEW_PRODUCT", "VIEW" -> "Xem chi tiết sản phẩm";
            case "SEARCH" -> "Tìm kiếm sản phẩm";
            case "CHAT_INITIATED", "CHAT" -> "Gửi tin nhắn tư vấn";
            case "ORDER_CREATED", "ORDER" -> "Tạo đơn hàng mới";
            case "PAYMENT_COMPLETED" -> "Thanh toán thành công";
            default -> eventName;
        };
    }
}
