package com.ecom.cart.web;

import com.ecom.cart.model.CartItem;
import com.ecom.cart.repo.CartRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    public record AddItemRequest(@NotBlank String productId,
                                 @NotNull @Min(1) Integer quantity,
                                 @NotNull @DecimalMin("0.0") BigDecimal price) {}

    private final CartRepository carts;
    private final long ttlDays;

    public CartController(CartRepository carts, @Value("${app.cart-ttl-days}") long ttlDays) {
        this.carts = carts;
        this.ttlDays = ttlDays;
    }

    @GetMapping("/{userId}")
    public Map<String, Object> get(@PathVariable String userId) {
        List<CartItem> items = carts.findByUser(userId);
        BigDecimal total = items.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("userId", userId, "items", items, "total", total);
    }

    @PostMapping("/{userId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartItem add(@PathVariable String userId, @Valid @RequestBody AddItemRequest req) {
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setProductId(req.productId());
        item.setQuantity(req.quantity());
        item.setPrice(req.price());
        item.setExpiresAt(Instant.now().plus(Duration.ofDays(ttlDays)).getEpochSecond());
        carts.save(item);
        return item;
    }

    @DeleteMapping("/{userId}/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String userId, @PathVariable String productId) {
        carts.delete(userId, productId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String userId) {
        carts.findByUser(userId).forEach(i -> carts.delete(userId, i.getProductId()));
    }
}
