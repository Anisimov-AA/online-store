package com.onlinestore.warehouse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Counters shared by all consumer threads, so every update must be thread safe. */
public class OrderStats {

    private final AtomicLong totalOrders = new AtomicLong();
    private final Map<Integer, LongAdder> quantityByProduct = new ConcurrentHashMap<>();

    public void record(Order order) {
        for (Order.Item item : order.items()) {
            quantityByProduct.computeIfAbsent(item.productId(), id -> new LongAdder()).add(item.quantity());
        }
        totalOrders.incrementAndGet();
    }

    public long totalOrders() {
        return totalOrders.get();
    }

    public int distinctProducts() {
        return quantityByProduct.size();
    }
}
