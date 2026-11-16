package com.onlinestore.warehouse;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The order message sent by the shopping cart service. */
public record Order(
        @JsonProperty("order_id") int orderId,
        @JsonProperty("shopping_cart_id") int shoppingCartId,
        List<Item> items) {

    public record Item(@JsonProperty("product_id") int productId, int quantity) {}
}
