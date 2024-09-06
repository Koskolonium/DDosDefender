package ac.kosko.dDoSDefender.Network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionRejector extends ChannelInboundHandlerAdapter {

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