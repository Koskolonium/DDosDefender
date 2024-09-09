package ac.kosko.dDoSDefender.Network;

import io.netty.channel.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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
        // Add the ConnectionRejector to the pipeline of this new connection.
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new ConnectionRejector(plugin));
    }
}

/**
 * This class limits the number of connection attempts to the server per second.
 * It uses a Semaphore to keep track of available connection slots and rejects any
 * connection attempt that exceeds the allowed number of connections per second.
 */
class ConnectionRejector extends ChannelInboundHandlerAdapter {

    // The maximum number of connections allowed per second.
    private static final int MAX_CONNECTIONS_PER_SECOND = 20;

    // A semaphore to manage the available "slots" for new connections.
    private final Semaphore semaphore = new Semaphore(MAX_CONNECTIONS_PER_SECOND);

    // An atomic counter to track the number of connections in the current second.
    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    private final JavaPlugin plugin;

    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;

        // Schedule a task to reset the connection counter and the semaphore every second.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                connectionCounter.set(0); // Reset the connection count.
                semaphore.drainPermits(); // Remove all permits.
                semaphore.release(MAX_CONNECTIONS_PER_SECOND); // Refill the semaphore to its max capacity.
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 20L, 20L); // Run every 20 ticks, which is equivalent to 1 second in Minecraft.
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // If a permit is available (i.e., less than MAX_CONNECTIONS_PER_SECOND), allow the connection.
        if (semaphore.tryAcquire()) {
            connectionCounter.incrementAndGet(); // Increment the connection count.
            super.channelRegistered(ctx); // Proceed with the connection.
        } else {
            // Reject the connection immediately if the limit is exceeded.
            ctx.close();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        try {
            // Release a permit when the connection is closed to allow new connections.
            semaphore.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}