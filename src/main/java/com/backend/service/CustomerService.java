package com.backend.service;

import java.util.List;

import org.springframework.data.domain.Page;

import com.backend.dto.CreateCustomerRequest;
import com.backend.dto.CustomerDetailResponse;
import com.backend.dto.CustomerInteractionResponse;
import com.backend.dto.CustomerResponse;
import com.backend.dto.MergeCustomersRequest;
import com.backend.dto.UpdateCustomerRequest;

public interface CustomerService {

    Page<CustomerResponse> getCustomerList(String tenantId, String search, String status, int page, int size);

    CustomerDetailResponse getCustomerDetail(String tenantId, String id);

    CustomerResponse createCustomer(String tenantId, CreateCustomerRequest request);

    CustomerResponse updateCustomer(String tenantId, String id, UpdateCustomerRequest request);

    void deleteCustomer(String tenantId, String id);

    List<CustomerResponse> getPotentialDuplicates(String tenantId, String customerId);

    List<CustomerInteractionResponse> getInteractions(String tenantId, String customerId);

    void mergeCustomers(String tenantId, MergeCustomersRequest request, String userId);
}
