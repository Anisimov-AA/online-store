package com.onlinestore.product.controller;

import com.onlinestore.product.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    /** 0.0 = good service. The "bad" copy runs with 0.5 and fails half of the requests on purpose. */
    private final double failureRate;

    /** Requests seen in the last 10 s. Logged so the ATW test can compare traffic per copy in CloudWatch. */
    private final AtomicLong recentRequests = new AtomicLong();

    public ProductController(@Value("${product.failure-rate}") double failureRate) {
        this.failureRate = failureRate;
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long count = recentRequests.getAndSet(0);
            if (count > 0) {
                log.info("requests in last 10s: {} (failure rate {})", count, failureRate);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @PostMapping("/product")
    public ResponseEntity<Map<String, Object>> createProduct(@RequestBody Product product) {
        recentRequests.incrementAndGet();
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "SERVICE_UNAVAILABLE", "message", "simulated failure"));
        }
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of("product_id", product.getProductId()));
    }

    /** Load balancer health check. Always 200, so the bad copy stays in service and ATW has something to show. */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
