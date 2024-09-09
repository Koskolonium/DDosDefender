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

    // A thread-safe map to store the different network handlers (ChannelInitializers) to be injected.
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
    }

    /**
     * Injects all registered custom network handlers into the server's pipeline.
     * This process will allow the plugin to apply custom logic during player connection.
     */
    public static void inject() {
        injectAcceptors(); // Injects the custom handlers into the network pipeline.
    }

    /**
     * Finds the connection entry points in the server and injects the custom handlers there.
     * This ensures the plugin can intercept connections at the earliest stage.
     */
    public static void injectAcceptors() {
        if (!injected) {
            try {
                // Get the internal Minecraft server instance.
                final Object nmsServer = NMSUtil.getServerInstance();
                // Retrieve the list of active connection channels (ChannelFutures).
                final List<ChannelFuture> channelFutures = NMSUtil.getServerChannelFutures(nmsServer);

                // Inject the custom handlers into the pipeline of each channel.
                for (final ChannelFuture channelFuture : channelFutures) {
                    injectAcceptor(channelFuture.channel().pipeline());
                }

                injected = true; // Mark the injection as completed.
            } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: " + e.getClass().getSimpleName());
                e.printStackTrace();
            }
        }
    }

    /**
     * Adds the custom handlers (from the initializer map) to the given channel's pipeline.
     *
     * @param serverChannelPipeline The network pipeline where handlers will be added.
     */
    private static void injectAcceptor(final ChannelPipeline serverChannelPipeline) {
        // Insert a new handler at the very beginning of the pipeline.
        serverChannelPipeline.addFirst("ConnectionPipelineInjectorAcceptor", new ChannelInboundHandlerAdapter() {

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                // Let other handlers process the event before applying our custom logic.
                super.channelRead(ctx, msg);

                // The 'childChannel' represents an individual connection.
                final Channel childChannel = (Channel) msg;

                // Add all custom handlers from the map to this new connection's pipeline.
                CHANNEL_INITIALIZER_MAP.forEach((name, initializer) -> {
                    childChannel.pipeline().addLast(name, initializer);
                });
            }
        });
    }
}