package ac.kosko.dDoSDefender.NetworkUtils;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.util.List;
import io.netty.channel.ChannelFuture;
import lombok.experimental.UtilityClass;

/**
 * Utility class that helps access Minecraft's internal server objects using reflection.
 * Provides methods to get the server instance and its connection-related data.
 */
@UtilityClass
public class NMSUtil {

    // Base package for Minecraft server classes, depending on the Minecraft version.
    private static final String OBC_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();
    private static final String OBC_VERSION_STRING = OBC_PACKAGE.split("\\.").length > 3 ? OBC_PACKAGE.split("\\.")[3] : "";
    private static final boolean USE_MODERN_NMS_NAMES = OBC_VERSION_STRING.isEmpty() || parseVersion(OBC_VERSION_STRING) >= 18; // 1.18+

    // Minecraft 1.17+ and later versions dropped the 'net.minecraft.server.VERSION' structure.
    private static final String NMS_PACKAGE = USE_MODERN_NMS_NAMES ? "net.minecraft.server" : "net.minecraft.server." + OBC_VERSION_STRING;

    /**
     * Retrieves a specific Minecraft server class using either its modern or legacy name, depending on the server version.
     *
     * @param legacyName The class name for older versions of Minecraft.
     * @param modernName The class name for modern versions of Minecraft.
     * @return The Class object for the requested class.
     * @throws ClassNotFoundException If the class could not be found.
     */
    public Class<?> getNMSClass(final String legacyName, final String modernName) throws ClassNotFoundException {
        return Class.forName(NMS_PACKAGE + "." + (USE_MODERN_NMS_NAMES ? modernName : legacyName));
    }

    /**
     * Gets the main Minecraft server instance through reflection.
     *
     * @return The Minecraft server instance.
     * @throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException If reflection fails.
     */
    public Object getServerInstance() throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        final Class<?> minecraftServerClass = getNMSClass("MinecraftServer", "MinecraftServer");
        final Field serverField = ReflectiveUtil.getFieldByType(minecraftServerClass, minecraftServerClass);
        return ReflectiveUtil.getFieldValue(null, serverField); // static field, so pass 'null'
    }

    /**
     * Retrieves the list of channel futures (network connection points) from the server.
     *
     * @param server The Minecraft server instance.
     * @return A list of ChannelFuture objects representing active connections.
     * @throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException If reflection fails.
     */
    public List<ChannelFuture> getServerChannelFutures(final Object server) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        final Class<?> serverConnectionClass = getNMSClass("ServerConnection", "network.ServerConnection");
        final Field serverConnectionField = ReflectiveUtil.getFieldByType(server.getClass(), serverConnectionClass);
        final Object serverConnection = ReflectiveUtil.getFieldValue(server, serverConnectionField);

        final Field channelFuturesField = ReflectiveUtil.getFieldByType(serverConnection.getClass(), List.class);
        return ReflectiveUtil.getFieldValue(serverConnection, channelFuturesField);
    }

    /**
     * Parses the version string to get the major version number.
     *
     * @param versionString The version string from the package.
     * @return The major version number, or -1 if parsing fails.
     */
    private int parseVersion(String versionString) {
        try {
            String[] parts = versionString.split("_");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            return -1; // If the version can't be parsed, return -1.
        }
    }
}