package com.onlinestore.cart;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends orders to the warehouse queue.
 * One connection (a socket, slow to open) is shared; channels (cheap lanes inside it) are kept in a pool,
 * so each request borrows a ready channel instead of opening a new one.
 */
@Component
public class OrderPublisher implements DisposableBean {

    public static final String QUEUE = "orders";
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final Connection connection;
    private final GenericObjectPool<Channel> channels;
    private final JsonMapper json;

    public OrderPublisher(@Value("${rabbitmq.host}") String host,
                          @Value("${rabbitmq.channel-pool-size}") int poolSize,
                          JsonMapper json) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        this.connection = factory.newConnection("shopping-cart");
        this.json = json;

        GenericObjectPoolConfig<Channel> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolSize);
        config.setMaxIdle(poolSize);
        this.channels = new GenericObjectPool<>(new ChannelFactory(connection), config);
    }

    /** Publishes the order and blocks until RabbitMQ confirms it has the message (Publisher Confirms). */
    public void publish(OrderMessage order) throws Exception {
        byte[] body = json.writeValueAsBytes(order);
        Channel channel = channels.borrowObject();
        try {
            channel.basicPublish("", QUEUE, MessageProperties.PERSISTENT_TEXT_PLAIN, body);
            channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            channels.returnObject(channel);
        } catch (Exception e) {
            channels.invalidateObject(channel); // a failed channel is closed, never reused
            throw e;
        }
    }

    @Override
    public void destroy() throws Exception {
        channels.close();
        connection.close();
    }

    /** Tells the pool how to make a channel: declare the queue and switch on confirms. */
    private static class ChannelFactory extends BasePooledObjectFactory<Channel> {
        private final Connection connection;

        ChannelFactory(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Channel create() throws Exception {
            Channel channel = connection.createChannel();
            channel.queueDeclare(QUEUE, true, false, false, null); // durable queue
            channel.confirmSelect();
            return channel;
        }

        @Override
        public PooledObject<Channel> wrap(Channel channel) {
            return new DefaultPooledObject<>(channel);
        }

        @Override
        public void destroyObject(PooledObject<Channel> pooled) throws Exception {
            if (pooled.getObject().isOpen()) {
                pooled.getObject().close();
            }
        }

        @Override
        public boolean validateObject(PooledObject<Channel> pooled) {
            return pooled.getObject().isOpen();
        }
    }
}
