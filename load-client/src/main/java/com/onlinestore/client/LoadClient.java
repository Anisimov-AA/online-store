package com.onlinestore.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multithreaded load tester. N threads share one counter of requests left and send until it reaches 0.
 *
 * Usage: java -jar load-client.jar <mode> <baseUrl> <threads> <totalRequests>
 *   mode = product   POST /product (for the ATW test)
 *   mode = checkout  POST /shopping-carts/{id}/checkout (for the RabbitMQ test)
 */
public class LoadClient {

    private static final int MAX_RETRIES = 5;
    private static final String TEST_CARD = "1234-5678-9012-3456"; // fake number, never a real card

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "checkout";
        String baseUrl = args.length > 1 ? args[1] : "http://localhost:8081";
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int total = args.length > 3 ? Integer.parseInt(args[3]) : 200_000;

        HttpClient setupHttp = newHttpClient();
        URI target;
        String okBody;
        int okStatus;
        if (mode.equals("product")) {
            target = URI.create(baseUrl + "/product");
            okBody = null; // a new random product per request
            okStatus = 201;
        } else {
            int cartId = createCart(setupHttp, baseUrl);
            target = URI.create(baseUrl + "/shopping-carts/" + cartId + "/checkout");
            okBody = "{\"credit_card_number\":\"" + TEST_CARD + "\"}";
            okStatus = 200;
            System.out.println("Checking out cart " + cartId);
        }

        AtomicInteger left = new AtomicInteger(total);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long start = System.currentTimeMillis();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                HttpClient http = newHttpClient(); // one client per thread
                while (left.getAndDecrement() > 0) {
                    String body = okBody != null ? okBody : randomProduct();
                    int status = send(http, target, body);
                    if (status == okStatus) {
                        ok.incrementAndGet();
                    } else if (status == 402) {
                        declined.incrementAndGet(); // the card was declined: a normal answer, not an error
                    } else {
                        failed.incrementAndGet();
                    }
                }
            });
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) {
            worker.join();
        }
        long wallMs = System.currentTimeMillis() - start;

        System.out.println("Mode:                " + mode);
        System.out.println("Threads:             " + threads);
        System.out.println("Successful requests: " + (ok.get() + declined.get())
                + "  (" + ok.get() + " " + okStatus + ", " + declined.get() + " declined 402)");
        System.out.println("Failed requests:     " + failed.get());
        System.out.println("Wall time:           " + wallMs + " ms");
        System.out.printf("Throughput:          %.1f req/s%n", total / (wallMs / 1000.0));
    }

    /** Sends one POST, retrying network errors and 5xx answers. Returns the final status, or -1. */
    private static int send(HttpClient http, URI uri, String body) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        int status = -1;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 500) {
                    return status;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (Exception e) {
                status = -1; // network error: try again
            }
        }
        return status;
    }

    /** Creates one cart with one item before the test starts; every checkout then uses this cart. */
    private static int createCart(HttpClient http, String baseUrl) throws Exception {
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/shopping-cart"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customer_id\":5}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        Matcher id = Pattern.compile("\"shopping_cart_id\"\\s*:\\s*(\\d+)").matcher(created.body());
        if (created.statusCode() != 201 || !id.find()) {
            throw new IllegalStateException("could not create cart: " + created.statusCode() + " " + created.body());
        }
        int cartId = Integer.parseInt(id.group(1));
        send(http, URI.create(baseUrl + "/shopping-carts/" + cartId + "/addItem"), "{\"product_id\":42,\"quantity\":3}");
        return cartId;
    }

    private static String randomProduct() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int id = r.nextInt(1, Integer.MAX_VALUE);
        return "{\"product_id\":" + id + ",\"sku\":\"SKU-" + id + "\",\"manufacturer\":\"Acme\","
                + "\"category_id\":" + r.nextInt(1, 1000) + ",\"weight\":" + r.nextInt(0, 10_000)
                + ",\"some_other_id\":" + r.nextInt(1, 1000) + "}";
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
