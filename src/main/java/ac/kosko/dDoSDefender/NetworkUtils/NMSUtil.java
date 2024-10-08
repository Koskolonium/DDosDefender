package ac.kosko.dDoSDefender.NetworkUtils;

import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Field;
import java.util.List;
import io.netty.channel.ChannelFuture;
import lombok.experimental.UtilityClass;

@UtilityClass
public class NMSUtil {
    private static final String OBC_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();
    private static final String OBC_VERSION_STRING = OBC_PACKAGE.split("\\.").length > 3 ? OBC_PACKAGE.split("\\.")[3] : "";
    private static final boolean USE_MODERN_NMS_NAMES = OBC_VERSION_STRING.isEmpty() || parseVersion() >= 18; // 1.18+
    private static final String NMS_PACKAGE = USE_MODERN_NMS_NAMES ? "net.minecraft.server" : "net.minecraft.server." + OBC_VERSION_STRING;

    public Class<?> getNMSClass(final String legacyName, final String modernName) throws ClassNotFoundException {
        return Class.forName(NMS_PACKAGE + "." + (USE_MODERN_NMS_NAMES ? modernName : legacyName));
    }

    public Object getServerInstance() throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        String craftServerClassName = OBC_PACKAGE + ".CraftServer";
        Class<?> craftServerClass = Class.forName(craftServerClassName);
        Field serverField = ReflectiveUtil.getFieldByType(craftServerClass, getNMSClass("MinecraftServer", "MinecraftServer"));
        Server bukkitServer = Bukkit.getServer();
        return ReflectiveUtil.getFieldValue(bukkitServer, serverField);
    }

    public List<ChannelFuture> getServerChannelFutures(final Object server) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        final Class<?> serverConnectionClass = getNMSClass("ServerConnection", "network.ServerConnection");
        final Field serverConnectionField = ReflectiveUtil.getFieldByType(server.getClass(), serverConnectionClass);
        final Object serverConnection = ReflectiveUtil.getFieldValue(server, serverConnectionField);
        final Field channelFuturesField = ReflectiveUtil.getFieldByType(serverConnection.getClass(), List.class);
        return ReflectiveUtil.getFieldValue(serverConnection, channelFuturesField);
    }

    private int parseVersion() {
        try {
            String[] parts = OBC_VERSION_STRING.split("_");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}