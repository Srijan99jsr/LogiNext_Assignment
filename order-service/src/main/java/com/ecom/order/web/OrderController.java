package com.ecom.order.web;

import com.ecom.order.model.CustomerOrder;
import com.ecom.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record PlaceOrderRequest(@NotBlank String userId, @NotEmpty List<OrderService.ItemRequest> items) {}

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerOrder place(@Valid @RequestBody PlaceOrderRequest req) {
        return orders.place(req.userId(), req.items());
    }

    @GetMapping("/{id}")
    public CustomerOrder get(@PathVariable Long id) { return orders.get(id); }

    @GetMapping("/user/{userId}")
    public List<CustomerOrder> forUser(@PathVariable String userId) { return orders.forUser(userId); }
}
