package ac.kosko.dDoSDefender.Network;

import io.netty.channel.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionRejectorInitializer extends ChannelInitializer<Channel> {

    private final JavaPlugin plugin;

    public ConnectionRejectorInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new ConnectionRejector(plugin));
    }
}

class ConnectionRejector extends ChannelInboundHandlerAdapter {

    private static final int MAX_CONNECTIONS_PER_SECOND = 20;
    private final Semaphore semaphore = new Semaphore(MAX_CONNECTIONS_PER_SECOND);
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private final JavaPlugin plugin;

    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;
        // Schedule the task to reset the connection counter every second
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                connectionCounter.set(0);
                semaphore.drainPermits();
                semaphore.release(MAX_CONNECTIONS_PER_SECOND);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 20L, 20L); // Run every second (20 ticks)
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (semaphore.tryAcquire()) {
            connectionCounter.incrementAndGet();
            super.channelRegistered(ctx);
        } else {
            // Reject the connection attempt immediately
            ctx.close();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        try {
            semaphore.release(); // Release the permit when a connection is unregistered
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}