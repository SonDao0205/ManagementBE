package com.backend.service;

import com.backend.dto.OrderRequest;
import com.backend.dto.OrderResponse;
import com.backend.dto.OrderStatusUpdateRequest;
import com.backend.entity.OrderEntity;
import com.backend.entity.OrderItemEntity;
import com.backend.repository.OrderItemRepository;
import com.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    @Transactional(readOnly = true)
    public Page<OrderResponse> list(String tenantId, String search, String status, Pageable pageable) {
        Page<OrderEntity> page;

        boolean hasSearch = search != null && !search.isBlank();
        boolean hasStatus = status != null && !status.isBlank();

        if (hasSearch && hasStatus) {
            page = orderRepository.searchByKeywordAndStatus(tenantId, search, status, pageable);
        } else if (hasSearch) {
            page = orderRepository.searchByKeyword(tenantId, search, pageable);
        } else if (hasStatus) {
            page = orderRepository.findAllByTenantIdAndStatusAndDeletedAtIsNull(tenantId, status, pageable);
        } else {
            page = orderRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable);
        }

        return page.map(order -> {
            List<OrderItemEntity> items = orderItemRepository.findAllByOrderIdAndTenantId(order.getId(), tenantId);
            return OrderResponse.from(order, items);
        });
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(String tenantId, String id) {
        OrderEntity order = orderRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        List<OrderItemEntity> items = orderItemRepository.findAllByOrderIdAndTenantId(id, tenantId);
        return OrderResponse.from(order, items);
    }

    public OrderResponse create(String tenantId, OrderRequest request) {
        String orderId = nextId();

        // Compute totals from items
        BigDecimal totalAmount = request.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discountAmount = request.discountAmount() != null ? request.discountAmount() : BigDecimal.ZERO;
        // Bug fix: discount cannot exceed total amount
        if (discountAmount.compareTo(totalAmount) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Discount amount (" + discountAmount + ") cannot exceed total amount (" + totalAmount + ")");
        }
        BigDecimal finalAmount = totalAmount.subtract(discountAmount);

        // Generate order code
        String orderCode = "ORD-" + System.currentTimeMillis();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTenantId(tenantId);
        order.setOrderCode(orderCode);
        order.setMarketplace(request.marketplace());
        order.setCustomerName(request.customerName());
        order.setCustomerPhone(request.customerPhone());
        order.setShippingAddressJson(request.shippingAddressJson());
        order.setTotalAmount(totalAmount);
        order.setDiscountAmount(discountAmount);
        order.setFinalAmount(finalAmount);
        // Bug fix: default paymentStatus is COD (matches DB CHECK constraint: PAID|COD|REFUNDED), not 'UNPAID'
        order.setPaymentStatus(request.paymentStatus() != null && !request.paymentStatus().isBlank()
                ? request.paymentStatus() : "COD");
        order.setStatus("PENDING");
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);

        List<OrderItemEntity> itemEntities = request.items().stream().map(itemReq -> {
            OrderItemEntity item = new OrderItemEntity();
            item.setId(nextId());
            item.setOrderId(orderId);
            item.setTenantId(tenantId);
            item.setProductName(itemReq.productName());
            item.setSku(itemReq.sku());
            item.setVariantName(itemReq.variantName());
            item.setPrice(itemReq.price());
            item.setQuantity(itemReq.quantity());
            return item;
        }).toList();

        orderItemRepository.saveAll(itemEntities);

        return OrderResponse.from(order, itemEntities);
    }

    public OrderResponse updateStatus(String tenantId, String id, OrderStatusUpdateRequest request) {
        OrderEntity order = orderRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        order.setStatus(request.status());
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        List<OrderItemEntity> items = orderItemRepository.findAllByOrderIdAndTenantId(id, tenantId);
        return OrderResponse.from(order, items);
    }

    public void delete(String tenantId, String id) {
        OrderEntity order = orderRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        order.setDeletedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
    }

    private String nextId() {
        return UUID.randomUUID().toString();
    }
}
