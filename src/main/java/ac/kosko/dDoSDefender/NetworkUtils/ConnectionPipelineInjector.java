package ac.kosko.dDoSDefender.NetworkUtils;

import io.netty.channel.*;
import lombok.Data;
import lombok.NonNull;
import org.bukkit.Bukkit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for managing and injecting custom network handlers
 * into the server's connection pipeline. It allows the plugin to intercept
 * new player connections and apply custom logic before they reach the server.
 */
@Data
public class ConnectionPipelineInjector {
    private static final Map<String, ChannelInitializer<Channel>> CHANNEL_INITIALIZER_MAP = new ConcurrentHashMap<>();
    private static boolean injected;

    /**
     * Registers a new custom network handler (ChannelInitializer) to be added
     * to the server's connection pipeline.
     *
     * @param name A unique identifier for this handler.
     * @param initializer The custom network handler (ChannelInitializer).
     */
    public static void registerChannelInitializer(@NonNull final String name, @NonNull final ChannelInitializer<Channel> initializer) {
        CHANNEL_INITIALIZER_MAP.put(name, initializer);
        Bukkit.getLogger().info("Registered custom network handler: " + name);
    }

    /**
     * Injects all registered custom network handlers into the server's pipeline.
     * This process will allow the plugin to apply custom logic during player connection.
     */
    public static void inject() {
        Bukkit.getLogger().info("Injecting custom network handlers into the server's pipeline...");
        injectAcceptors();
    }

    /**
     * Finds the connection entry points in the server and injects the custom handlers there.
     * This ensures the plugin can intercept connections at the earliest stage.
     */
    public static void injectAcceptors() {
        if (!injected) {
            try {
                final Object nmsServer = NMSUtil.getServerInstance();
                Bukkit.getLogger().info("Retrieved internal Minecraft server instance.");
                final List<ChannelFuture> channelFutures = NMSUtil.getServerChannelFutures(nmsServer);
                Bukkit.getLogger().info("Retrieved list of active connection channels.");
                for (final ChannelFuture channelFuture : channelFutures) {
                    final ChannelPipeline pipeline = channelFuture.channel().pipeline();
                    CHANNEL_INITIALIZER_MAP.forEach((name, initializer) -> {
                        pipeline.addLast(name, initializer);
                        Bukkit.getLogger().info("Added custom handler '" + name + "' to connection pipeline.");
                    });
                }
                injected = true;
                Bukkit.getLogger().info("Custom network handlers injected successfully.");
            } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: " + e.getClass().getSimpleName());
                e.printStackTrace();
            }
        } else {
            Bukkit.getLogger().info("Custom network handlers already injected.");
        }
    }
}