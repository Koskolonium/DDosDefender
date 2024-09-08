package ac.kosko.dDoSDefender.NetworkUtils;

import ac.kosko.dDoSDefender.Network.ConnectionRejector;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.strikeless.bootstraphook.api.BootstrapHook;

public class ConnectionPipelineInjector {

    private static final Object lock = new Object();
    private static boolean injected = false;
    private static BootstrapHook bootstrapHook;

    public static void inject(JavaPlugin plugin) {
        synchronized (lock) {
            if (injected) {
                return;
            }
            injected = true;

            bootstrapHook = BootstrapHook.builder()
                    .channelInitializerName("connection_rejector")
                    .channelInitializer(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            if (pipeline.get("connection_rejector") == null) {
                                pipeline.addFirst("connection_rejector", new ConnectionRejector(plugin));
                            }
                        }
                    })
                    .build();

            try {
                bootstrapHook.inject();
            } catch (Exception ex) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }

    public static void remove() {
        synchronized (lock) {
            if (!injected) {
                return;
            }
            injected = false;

            bootstrapHook.eject();
        }
    }
}