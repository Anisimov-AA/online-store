package com.onlinestore.cart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One shopping cart: who owns it and how many of each product is inside. */
public class Cart {

    private final int customerId;
    private final Map<Integer, Integer> items = new ConcurrentHashMap<>();

    public Cart(int customerId) {
        this.customerId = customerId;
    }

    /** Adding the same product twice adds the quantities together. */
    public void addItem(int productId, int quantity) {
        items.merge(productId, quantity, Integer::sum);
    }

    public int getCustomerId() {
        return customerId;
    }

    public Map<Integer, Integer> getItems() {
        return items;
    }
}
