package com.onlinestore.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulated warehouse: N consumer threads read orders from the queue, count them,
 * and only then send a manual ack so RabbitMQ can delete the message.
 */
public class Warehouse {

    private static final String QUEUE = "orders";

    public static void main(String[] args) throws Exception {
        String host = env("RABBITMQ_HOST", "localhost");
        int threads = Integer.parseInt(env("WAREHOUSE_THREADS", "16"));
        int prefetch = Integer.parseInt(env("PREFETCH", "10"));

        OrderStats stats = new OrderStats();
        ObjectMapper json = new ObjectMapper();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        // the callbacks run on this pool, so its size = the real number of consumer threads
        ExecutorService consumerPool = Executors.newFixedThreadPool(threads);
        Connection connection = factory.newConnection(consumerPool, "warehouse");

        for (int i = 0; i < threads; i++) {
            Channel channel = connection.createChannel(); // one channel per consumer, never shared
            channel.queueDeclare(QUEUE, true, false, false, null);
            channel.basicQos(prefetch); // at most this many unacked messages per consumer

            DeliverCallback onMessage = (tag, delivery) -> {
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                try {
                    Order order = json.readValue(delivery.getBody(), Order.class);
                    stats.record(order);
                    channel.basicAck(deliveryTag, false); // "written down, you can delete it"
                } catch (Exception e) {
                    channel.basicNack(deliveryTag, false, false); // broken message: drop it
                }
            };
            channel.basicConsume(QUEUE, false, onMessage, tag -> { }); // autoAck = false → manual acks
        }

        ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor();
        progress.scheduleAtFixedRate(
                () -> System.out.println("orders so far: " + stats.totalOrders()), 5, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            progress.shutdownNow();
            System.out.println("Warehouse shutting down.");
            System.out.println("Total orders: " + stats.totalOrders());
            System.out.println("Distinct products ordered: " + stats.distinctProducts());
        }));

        System.out.printf("Warehouse started: %d consumer threads, prefetch %d, broker %s%n", threads, prefetch, host);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
