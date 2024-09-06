package ac.kosko.dDoSDefender.NetworkUtils;

import ac.kosko.dDoSDefender.Network.ConnectionRejector;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.util.List;

public class ConnectionPipelineInjector {

    private static final Object lock = new Object();
    private static boolean injected = false;

    public static void inject() {
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
                // Access the MinecraftServer instance via CraftServer
                craftServer = Bukkit.getServer();
                craftServerField = craftServer.getClass().getDeclaredField("server");
                craftServerField.setAccessible(true);
                minecraftServer = craftServerField.get(craftServer);

                // Access the serverConnection field where connections are managed
                minecraftServerField = minecraftServer.getClass().getDeclaredField("serverConnection");
                minecraftServerField.setAccessible(true);
                serverConnection = minecraftServerField.get(minecraftServer);

                // Access the list of channels (NetworkManagers) in the server connection
                serverConnectionField = serverConnection.getClass().getDeclaredField("connections");
                serverConnectionField.setAccessible(true);
                List<?> networkManagers = (List<?>) serverConnectionField.get(serverConnection);

                // Inject the ConnectionRejector into each NetworkManager's channel pipeline
                for (Object networkManager : networkManagers) {
                    Channel channel = (Channel) getFieldValue(networkManager, "channel");
                    if (channel != null) {
                        ChannelPipeline pipeline = channel.pipeline();
                        if (pipeline.get("connection_rejector") == null) {
                            pipeline.addFirst("connection_rejector", new ConnectionRejector());
                        }
                    }
                }

            } catch (Exception e) {
                Bukkit.getLogger().severe("Failed to inject Netty handler: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Close Field objects to prevent resource leaks
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
                // Access the MinecraftServer instance via CraftServer
                craftServer = Bukkit.getServer();
                craftServerField = craftServer.getClass().getDeclaredField("server");
                craftServerField.setAccessible(true);
                minecraftServer = craftServerField.get(craftServer);

                // Access the serverConnection field where connections are managed
                minecraftServerField = minecraftServer.getClass().getDeclaredField("serverConnection");
                minecraftServerField.setAccessible(true);
                serverConnection = minecraftServerField.get(minecraftServer);

                // Access the list of channels (NetworkManagers) in the server connection
                serverConnectionField = serverConnection.getClass().getDeclaredField("connections");
                serverConnectionField.setAccessible(true);
                List<?> networkManagers = (List<?>) serverConnectionField.get(serverConnection);

                // Remove the ConnectionRejector from each NetworkManager's channel pipeline
                for (Object networkManager : networkManagers) {
                    Channel channel = (Channel) getFieldValue(networkManager, "channel");
                    if (channel != null) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.remove("connection_rejector"); // Remove the handler
                    }
                }

            } catch (Exception e) {
                Bukkit.getLogger().severe("Failed to remove Netty handler: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Close Field objects to prevent resource leaks
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
        field.setAccessible(false); // Close the Field object
        return value;
    }
}