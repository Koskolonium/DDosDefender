package ac.kosko.dDoSDefender.Network;

import io.netty.channel.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is responsible for initializing the ConnectionRejector and adding it to each new connection's pipeline.
 * It ensures that the ConnectionRejector is correctly placed to limit incoming connection attempts.
 */
public class ConnectionRejectorInitializer extends ChannelInitializer<Channel> {

    private final JavaPlugin plugin;

    public ConnectionRejectorInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        // Add the ConnectionRejector as the first handler in the pipeline
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addFirst("connectionRejector", new ConnectionRejector(plugin));
    }
}

/**
 * This class limits the number of connection attempts to the server per second.
 * It uses a Semaphore to keep track of available connection slots and rejects any
 * connection attempt that exceeds the allowed number of connections per second.
 */
class ConnectionRejector extends ChannelInboundHandlerAdapter {

    private static final int MAX_CONNECTIONS_PER_SECOND = 20;
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<Long, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();

    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;

        // Schedule a task to reset the connection counts every second
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                long currentSecond = System.currentTimeMillis() / 1000;
                connectionCounts.remove(currentSecond - 1); // Remove previous second's count
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 20L, 20L); // Run every 20 ticks, which is equivalent to 1 second in Minecraft
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        long currentSecond = System.currentTimeMillis() / 1000;
        AtomicInteger connectionCount = connectionCounts.computeIfAbsent(currentSecond, k -> new AtomicInteger(0));
        if (connectionCount.incrementAndGet() <= MAX_CONNECTIONS_PER_SECOND) {
            Bukkit.getLogger().info("Connection accepted from " + ctx.channel().remoteAddress());
            super.channelRegistered(ctx); // Proceed with the connection
        } else {
            Bukkit.getLogger().info("Connection rejected from " + ctx.channel().remoteAddress() + " due to rate limit exceeded.");
            ctx.close();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        try {
            long currentSecond = System.currentTimeMillis() / 1000;
            AtomicInteger connectionCount = connectionCounts.get(currentSecond);
            if (connectionCount != null) {
                connectionCount.decrementAndGet();
            }
            Bukkit.getLogger().info("Connection closed from " + ctx.channel().remoteAddress());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}