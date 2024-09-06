package ac.kosko.dDoSDefender.NetworkUtils;

import ac.kosko.dDoSDefender.Network.ConnectionRejector;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.List;

public class ConnectionPipelineInjector {

    private static final Object lock = new Object();
    private static boolean injected = false;

    public static void inject(JavaPlugin plugin) {
        synchronized (lock) {
            if (injected) {
                return;
            }
            injected = true;

            Object craftServer = null;
            Object minecraftServer = null;
            Object serverConnection = null;
            Field craftServerField = null;
            Field minecraftServerField = null;
            Field serverConnectionField = null;

            try {
                craftServer = Bukkit.getServer();
                craftServerField = craftServer.getClass().getDeclaredField("server");
                craftServerField.setAccessible(true);
                minecraftServer = craftServerField.get(craftServer);

                if (minecraftServer == null) {
                    // Try to get the server field from the console
                    craftServerField = craftServer.getClass().getDeclaredField("console");
                    craftServerField.setAccessible(true);
                    Object console = craftServerField.get(craftServer);

                    if (console != null) {
                        craftServerField = console.getClass().getDeclaredField("server");
                        craftServerField.setAccessible(true);
                        minecraftServer = craftServerField.get(console);
                    }
                }

                if (minecraftServer != null) {
                    minecraftServerField = minecraftServer.getClass().getDeclaredField("serverConnection");
                    minecraftServerField.setAccessible(true);
                    serverConnection = minecraftServerField.get(minecraftServer);

                    if (serverConnection != null) {
                        serverConnectionField = serverConnection.getClass().getDeclaredField("connections");
                        serverConnectionField.setAccessible(true);
                        List<?> networkManagers = (List<?>) serverConnectionField.get(serverConnection);

                        for (Object networkManager : networkManagers) {
                            Channel channel = (Channel) getFieldValue(networkManager, "channel");
                            if (channel != null) {
                                ChannelPipeline pipeline = channel.pipeline();
                                if (pipeline.get("connection_rejector") == null) {
                                    pipeline.addFirst("connection_rejector", new ConnectionRejector(plugin));
                                }
                            }
                        }
                    }
                }

            } catch (Exception e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (craftServerField != null) {
                    craftServerField.setAccessible(false);
                }
                if (minecraftServerField != null) {
                    minecraftServerField.setAccessible(false);
                }
                if (serverConnectionField != null) {
                    serverConnectionField.setAccessible(false);
                }
            }
        }
    }

    public static void remove() {
        synchronized (lock) {
            if (!injected) {
                return;
            }
            injected = false;

            Object craftServer = null;
            Object minecraftServer = null;
            Object serverConnection = null;
            Field craftServerField = null;
            Field minecraftServerField = null;
            Field serverConnectionField = null;

            try {
                craftServer = Bukkit.getServer();
                craftServerField = craftServer.getClass().getDeclaredField("server");
                craftServerField.setAccessible(true);
                minecraftServer = craftServerField.get(craftServer);

                if (minecraftServer == null) {
                    // Try to get the server field from the console
                    craftServerField = craftServer.getClass().getDeclaredField("console");
                    craftServerField.setAccessible(true);
                    Object console = craftServerField.get(craftServer);

                    if (console != null) {
                        craftServerField = console.getClass().getDeclaredField("server");
                        craftServerField.setAccessible(true);
                        minecraftServer = craftServerField.get(console);
                    }
                }

                if (minecraftServer != null) {
                    minecraftServerField = minecraftServer.getClass().getDeclaredField("serverConnection");
                    minecraftServerField.setAccessible(true);
                    serverConnection = minecraftServerField.get(minecraftServer);

                    if (serverConnection != null) {
                        serverConnectionField = serverConnection.getClass().getDeclaredField("connections");
                        serverConnectionField.setAccessible(true);
                        List<?> networkManagers = (List<?>) serverConnectionField.get(serverConnection);

                        for (Object networkManager : networkManagers) {
                            Channel channel = (Channel) getFieldValue(networkManager, "channel");
                            if (channel != null) {
                                ChannelPipeline pipeline = channel.pipeline();
                                pipeline.remove("connection_rejector");
                            }
                        }
                    }
                }

            } catch (Exception e) {
                Bukkit.getLogger().severe("Failed to remove Netty handler: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (craftServerField != null) {
                    craftServerField.setAccessible(false);
                }
                if (minecraftServerField != null) {
                    minecraftServerField.setAccessible(false);
                }
                if (serverConnectionField != null) {
                    serverConnectionField.setAccessible(false);
                }
            }
        }
    }

    private static Object getFieldValue(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(instance);
        field.setAccessible(false);
        return value;
    }
}
