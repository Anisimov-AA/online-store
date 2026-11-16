package com.onlinestore.cart;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class CartController {

    record CreateCartRequest(@JsonProperty("customer_id") Integer customerId) {}
    record AddItemRequest(@JsonProperty("product_id") Integer productId, Integer quantity) {}
    record CheckoutRequest(@JsonProperty("credit_card_number") String creditCardNumber) {}

    private final Map<Integer, Cart> carts = new ConcurrentHashMap<>();
    private final AtomicInteger nextCartId = new AtomicInteger(1);
    private final AtomicInteger nextOrderId = new AtomicInteger(1);
    private final CreditCardClient creditCard;
    private final OrderPublisher orders;

    public CartController(CreditCardClient creditCard, OrderPublisher orders) {
        this.creditCard = creditCard;
        this.orders = orders;
    }

    @PostMapping("/shopping-cart")
    public ResponseEntity<Map<String, Object>> createCart(@RequestBody CreateCartRequest req) {
        if (req.customerId() == null || req.customerId() < 1) {
            return error(HttpStatus.BAD_REQUEST, "customer_id must be 1 or more");
        }
        int cartId = nextCartId.getAndIncrement();
        carts.put(cartId, new Cart(req.customerId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("shopping_cart_id", cartId));
    }

    @PostMapping("/shopping-carts/{cartId}/addItem")
    public ResponseEntity<Map<String, Object>> addItem(@PathVariable int cartId, @RequestBody AddItemRequest req) {
        Cart cart = carts.get(cartId);
        if (cart == null) {
            return error(HttpStatus.NOT_FOUND, "cart " + cartId + " not found");
        }
        if (req.productId() == null || req.productId() < 1) {
            return error(HttpStatus.BAD_REQUEST, "product_id must be 1 or more");
        }
        if (req.quantity() == null || req.quantity() < 1 || req.quantity() > 10_000) {
            return error(HttpStatus.BAD_REQUEST, "quantity must be 1..10000");
        }
        cart.addItem(req.productId(), req.quantity());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/shopping-carts/{cartId}/checkout")
    public ResponseEntity<Map<String, Object>> checkout(@PathVariable int cartId, @RequestBody CheckoutRequest req) {
        Cart cart = carts.get(cartId);
        if (cart == null) {
            return error(HttpStatus.NOT_FOUND, "cart " + cartId + " not found");
        }

        int ccaStatus = creditCard.authorize(req.creditCardNumber());
        if (ccaStatus == 400) {
            return error(HttpStatus.BAD_REQUEST, "bad card format");
        }
        if (ccaStatus == 402) {
            return error(HttpStatus.PAYMENT_REQUIRED, "card declined");
        }
        if (ccaStatus != 200) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "credit card service answered " + ccaStatus);
        }

        int orderId = nextOrderId.getAndIncrement();
        List<OrderMessage.Item> items = cart.getItems().entrySet().stream()
                .map(e -> new OrderMessage.Item(e.getKey(), e.getValue()))
                .toList();
        try {
            orders.publish(new OrderMessage(orderId, cartId, items));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "could not send order to warehouse");
        }
        return ResponseEntity.ok(Map.of("order_id", orderId));
    }

    /** Load balancer health check. */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", status.name(), "message", message));
    }
}
