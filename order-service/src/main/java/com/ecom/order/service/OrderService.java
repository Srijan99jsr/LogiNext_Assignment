package com.ecom.order.service;

import com.ecom.order.model.CustomerOrder;
import com.ecom.order.model.OrderLine;
import com.ecom.order.repo.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    public record ItemRequest(Long productId, Integer quantity) {}
    /** Shape of the JSON returned by product-service GET /api/products/{id}. */
    public record ProductView(Long id, String name, BigDecimal price, Integer stock) {}

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final RestClient productClient;
    private final RestClient cartClient;

    public OrderService(OrderRepository orders,
                        @Qualifier("productClient") RestClient productClient,
                        @Qualifier("cartClient") RestClient cartClient) {
        this.orders = orders;
        this.productClient = productClient;
        this.cartClient = cartClient;
    }

    /** Prices are always re-read from product-service; never trust prices sent by the browser. */
    @Transactional
    public CustomerOrder place(String userId, List<ItemRequest> items) {
        CustomerOrder order = new CustomerOrder();
        order.setUserId(userId);
        BigDecimal total = BigDecimal.ZERO;

        for (ItemRequest item : items) {
            ProductView p = fetchProduct(item.productId());
            if (p.stock() != null && p.stock() < item.quantity()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock for " + p.name());
            }
            order.getLines().add(new OrderLine(p.id(), p.name(), item.quantity(), p.price()));
            total = total.add(p.price().multiply(BigDecimal.valueOf(item.quantity())));
        }
        order.setTotalAmount(total);
        CustomerOrder saved = orders.save(order);
        log.info("Order {} placed for user {} total {}", saved.getId(), userId, total);
        clearCartQuietly(userId);
        return saved;
    }

    public CustomerOrder get(Long id) {
        return orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    public List<CustomerOrder> forUser(String userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private ProductView fetchProduct(Long id) {
        try {
            ProductView p = productClient.get().uri("/api/products/{id}", id).retrieve().body(ProductView.class);
            if (p == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product " + id);
            return p;
        } catch (RestClientException e) {
            log.error("product-service call failed for product {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product service unavailable");
        }
    }

    /** The order is already saved; a failed cart clean-up must not fail the checkout. */
    private void clearCartQuietly(String userId) {
        try {
            cartClient.delete().uri("/api/cart/{userId}", userId).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Could not clear cart for user {}: {}", userId, e.getMessage());
        }
    }
}
