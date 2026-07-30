package com.backend.service.impl;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.CreateStaffRequest;
import com.backend.dto.UpdateStaffRequest;
import com.backend.dto.StaffUserResponse;
import com.backend.entity.RoleEntity;
import com.backend.entity.TenantEntity;
import com.backend.entity.TenantUserCredentialEntity;
import com.backend.entity.TenantUserEntity;
import com.backend.entity.TenantUserRoleEntity;
import com.backend.repository.RoleRepository;
import com.backend.repository.TenantUserRepository;
import com.backend.repository.TenantUserRoleRepository;
import com.backend.repository.TenantRepository;
import com.backend.repository.TenantUserCredentialRepository;
import com.backend.service.AuthenticationException;
import com.backend.service.CskhAccountService;

@Service
public class CskhAccountServiceImpl implements CskhAccountService {

    private final TenantUserRepository tenantUserRepository;
    private final TenantUserRoleRepository tenantUserRoleRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantUserCredentialRepository tenantUserCredentialRepository;
    private final EntityManager entityManager;

    public CskhAccountServiceImpl(
            TenantUserRepository tenantUserRepository,
            TenantUserRoleRepository tenantUserRoleRepository,
            RoleRepository roleRepository,
            TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder,
            TenantUserCredentialRepository tenantUserCredentialRepository,
            EntityManager entityManager) {
        this.tenantUserRepository = tenantUserRepository;
        this.tenantUserRoleRepository = tenantUserRoleRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.tenantUserCredentialRepository = tenantUserCredentialRepository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffUserResponse> getStaffList(String tenantId) {
        List<TenantUserEntity> users = tenantUserRepository.findStaffByTenantId(tenantId);
        return users.stream()
                .map(user -> new StaffUserResponse(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getAvatarUrl(), // Maps avatarUrl field to phoneNumber (senior workaround for schema isolation)
                        Collections.singletonList("CS_AGENT"),
                        user.getStatus(),
                        user.getCreatedAt()
                ))
                .toList();
    }

    @Override
    @Transactional
    public StaffUserResponse createStaff(String tenantId, CreateStaffRequest request, String creatorUserId) {
        String email = request.email().trim().toLowerCase();
        if (tenantUserRepository.existsByEmail(email)) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_EXISTS", "Email này đã tồn tại trong hệ thống.");
        }

        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.BAD_REQUEST, "TENANT_NOT_FOUND", "Không tìm thấy thông tin Shop."));

        RoleEntity role = roleRepository.findByRoleCode("CS_AGENT")
                .orElseThrow(() -> new AuthenticationException(HttpStatus.BAD_REQUEST, "ROLE_NOT_FOUND", "Không tìm thấy vai trò CSKH trong hệ thống."));

        String staffId = UUID.randomUUID().toString();
        
        TenantUserEntity user = new TenantUserEntity();
        user.setId(staffId);
        user.setTenant(tenant);
        user.setEmail(email);
        user.setDisplayName(request.displayName().trim());
        user.setAvatarUrl(request.phoneNumber() != null ? request.phoneNumber().trim() : null); // Map phoneNumber to avatarUrl
        user.setStatus("ACTIVE");
        user.setCreatedAt(Instant.now());

        // Create credentials
        TenantUserCredentialEntity credential = new TenantUserCredentialEntity();
        credential.setTenantUserId(staffId);
        credential.setTenantUser(user);
        credential.setPasswordHash(passwordEncoder.encode(request.password()));
        credential.setPasswordAlgorithm("ARGON2ID");
        credential.setMustChangePassword(true); // force password change on first login
        credential.setCredentialVersion(1);
        
        user.setCredential(credential);
        entityManager.persist(user);
        entityManager.persist(credential);

        // Assign CSKH role
        TenantUserRoleEntity userRole = new TenantUserRoleEntity();
        userRole.setTenantUserId(staffId);
        userRole.setRoleId(role.getId());
        userRole.setTenantId(tenantId);
        userRole.setRoleScopeKey(role.getTenantScopeKey());
        userRole.setAssignedByUserId(creatorUserId);
        tenantUserRoleRepository.save(userRole);

        return new StaffUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                Collections.singletonList("CS_AGENT"),
                user.getStatus(),
                user.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public StaffUserResponse updateStaff(String tenantId, String staffId, UpdateStaffRequest request) {
        TenantUserEntity user = tenantUserRepository.findById(staffId)
                .filter(u -> u.getTenantId().equals(tenantId) && u.getDeletedAt() == null)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "Không tìm thấy tài khoản nhân viên."));

        user.setDisplayName(request.displayName().trim());
        user.setAvatarUrl(request.phoneNumber() != null ? request.phoneNumber().trim() : null);
        if (request.status() != null) {
            user.setStatus(request.status());
        }

        tenantUserRepository.save(user);

        return new StaffUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                Collections.singletonList("CS_AGENT"),
                user.getStatus(),
                user.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public StaffUserResponse toggleStaffStatus(String tenantId, String staffId, String status) {
        TenantUserEntity user = tenantUserRepository.findById(staffId)
                .filter(u -> u.getTenantId().equals(tenantId) && u.getDeletedAt() == null)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "Không tìm thấy tài khoản nhân viên."));

        if (!status.equals("ACTIVE") && !status.equals("LOCKED")) {
            throw new AuthenticationException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Trạng thái không hợp lệ.");
        }

        user.setStatus(status);
        tenantUserRepository.save(user);

        return new StaffUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                Collections.singletonList("CS_AGENT"),
                user.getStatus(),
                user.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public void deleteStaff(String tenantId, String staffId) {
        TenantUserEntity user = tenantUserRepository.findById(staffId)
                .filter(u -> u.getTenantId().equals(tenantId) && u.getDeletedAt() == null)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "Không tìm thấy tài khoản nhân viên."));

        // Soft delete by setting deletedAt
        user.setDeletedAt(Instant.now());
        tenantUserRepository.save(user);
    }

    @Override
    @Transactional
    public void resetPassword(String tenantId, String staffId, String password) {
        TenantUserEntity user = tenantUserRepository.findById(staffId)
                .filter(u -> u.getTenantId().equals(tenantId) && u.getDeletedAt() == null)
                .orElseThrow(() -> new AuthenticationException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "Không tìm thấy tài khoản nhân viên."));

        TenantUserCredentialEntity credential = user.getCredential();
        credential.setPasswordHash(passwordEncoder.encode(password));
        credential.setMustChangePassword(true); // force password change on first login
        credential.setCredentialVersion(credential.getCredentialVersion() + 1);
        credential.setPasswordChangedAt(Instant.now());

        // No explicit save/merge needed. Since credential is managed,
        // JPA will auto-flush the updates at the end of the transaction.
    }
}
