package com.backend.service;

import java.util.List;
import com.backend.dto.CreateStaffRequest;
import com.backend.dto.UpdateStaffRequest;
import com.backend.dto.StaffUserResponse;

public interface CskhAccountService {
    List<StaffUserResponse> getStaffList(String tenantId);
    
    StaffUserResponse createStaff(String tenantId, CreateStaffRequest request, String creatorUserId);
    
    StaffUserResponse updateStaff(String tenantId, String staffId, UpdateStaffRequest request);
    
    StaffUserResponse toggleStaffStatus(String tenantId, String staffId, String status);
    
    void deleteStaff(String tenantId, String staffId);
    
    void resetPassword(String tenantId, String staffId, String password);
}
