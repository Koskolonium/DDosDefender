package ac.kosko.dDoSDefender.NetworkUtils;

import io.netty.channel.*;
import lombok.Data;
import lombok.NonNull;
import org.bukkit.Bukkit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ConnectionPipelineInjector {
    private static final Map<String, ChannelInitializer<Channel>> CUSTOM_HANDLER_MAP = new ConcurrentHashMap<>();
    private static boolean injected;

    public static void registerChannelInitializer(@NonNull final String name, @NonNull final ChannelInitializer<Channel> initializer) {
        if (CUSTOM_HANDLER_MAP.containsKey(name)) {
            Bukkit.getLogger().warning("Custom handler '" + name + "' is already registered.");
            return;
        }
        CUSTOM_HANDLER_MAP.put(name, initializer);
        Bukkit.getLogger().info("Registered custom network handler: " + name);
    }

    public static void inject() {
        Bukkit.getLogger().info("Injecting custom network handlers into the server's pipeline...");
        injectAcceptors();
    }

    public static void injectAcceptors() {
        if (injected) {
            Bukkit.getLogger().info("Custom network handlers already injected.");
            return;
        }
        try {
            final Object nmsServer = NMSUtil.getServerInstance();
            Bukkit.getLogger().info("Retrieved internal Minecraft server instance.");
            final List<ChannelFuture> channelFutures = NMSUtil.getServerChannelFutures(nmsServer);
            Bukkit.getLogger().info("Retrieved list of active connection channels.");
            for (final ChannelFuture channelFuture : channelFutures) {
                final ChannelPipeline pipeline = channelFuture.channel().pipeline();
                CUSTOM_HANDLER_MAP.forEach(pipeline::addLast);
                Bukkit.getLogger().info("Added custom handlers to connection pipeline.");
            }
            injected = true;
            Bukkit.getLogger().info("Custom network handlers injected successfully.");
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            Bukkit.getLogger().severe("Failed to inject Netty handler: " + e.getClass().getSimpleName());
            e.printStackTrace();
        }
    }
}