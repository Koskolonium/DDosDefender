package ac.kosko.dDoSDefender.NetworkUtils;

import io.netty.channel.*;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

import static io.github.strikeless.bootstraphook.api.util.NMSUtil.getServerChannelFutures;
import static io.github.strikeless.bootstraphook.api.util.NMSUtil.getServerInstance;

/**
 * The main entry for the public API.
 */
@Data
public class ConnectionPipelineInjector {

    private static final Map<String, ChannelInitializer<Channel>> CHANNEL_INITIALIZER_MAP = new ConcurrentHashMap<>();
    private static boolean injected;

    /**
     * Registers a new {@link ChannelInitializer<Channel>} to be injected into the server's bootstrap.
     *
     * @param name          The name of the {@link ChannelInitializer<Channel>}.
     * @param initializer The {@link ChannelInitializer<Channel>} to be injected.
     */
    public static void registerChannelInitializer(@NonNull final String name, @NonNull final ChannelInitializer<Channel> initializer) {
        CHANNEL_INITIALIZER_MAP.put(name, initializer);
    }

    /**
     * Injects the registered {@link ChannelInitializer<Channel>}s into the server's bootstrap.
     */
    public static void inject() {
        injectAcceptors();
    }

    /**
     * Injects the {@link ChannelInitializer<Channel>} to the server's bootstrap.
     * This'll allow ConnectionPipelineInjector to inject the {@link ChannelInitializer<Channel>}s provided by dependants.
     * <p>
     * This does not need to be called manually, as {@link #inject()} automatically does it.
     */
    public static void injectAcceptors() {
        if (!injected) {
            try {
                final Object nmsServer = getServerInstance();
                final List<ChannelFuture> channelFutures = getServerChannelFutures(nmsServer);

                for (final ChannelFuture channelFuture : channelFutures) {
                    injectAcceptor(channelFuture.channel().pipeline());
                }

                injected = true;
            } catch (ClassNotFoundException e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: ClassNotFoundException");
                e.printStackTrace();
            } catch (NoSuchFieldException e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: NoSuchFieldException");
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: IllegalAccessException");
                e.printStackTrace();
            }
        }
    }

    /**
     * Injects the {@link ChannelInitializer<Channel>} to the given server's {@link ChannelPipeline}.
     *
     * @param serverChannelPipeline The {@link ChannelPipeline} which the {@link ChannelInitializer<Channel>}
     *                              will be added to.
     */
    private static void injectAcceptor(final ChannelPipeline serverChannelPipeline) {
        // We must add to the first position as ServerBootstrapAcceptor doesn't forward the event.
        serverChannelPipeline.addFirst("ConnectionPipelineInjectorAcceptor", new ChannelInboundHandlerAdapter() {

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                // Forward the event to other handlers before processing this ourselves.
                super.channelRead(ctx, msg);

                final Channel childChannel = (Channel) msg;

                // Add the ChannelInitializers to the client's/child's pipeline.
                CHANNEL_INITIALIZER_MAP.forEach((name, initializer) -> {
                    childChannel.pipeline().addLast(name, initializer);
                });
            }
        });
    }
}