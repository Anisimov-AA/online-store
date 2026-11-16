package com.onlinestore.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The "please ship this" message that goes to the warehouse through RabbitMQ. */
public record OrderMessage(
        @JsonProperty("order_id") int orderId,
        @JsonProperty("shopping_cart_id") int shoppingCartId,
        List<Item> items) {

    public record Item(@JsonProperty("product_id") int productId, int quantity) {}
}
