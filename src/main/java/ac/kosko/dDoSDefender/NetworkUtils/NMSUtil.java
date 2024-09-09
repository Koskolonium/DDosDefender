package ac.kosko.dDoSDefender.NetworkUtils;

import java.lang.reflect.Field;
import java.util.List;

import io.netty.channel.ChannelFuture;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;

@UtilityClass
public class NMSUtil {

    private static final String OBC_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();
    private static final String OBC_VERSION_STRING = OBC_PACKAGE.split("\\.")[3];
    private static final boolean USE_MODERN_NMS_NAMES = OBC_VERSION_STRING.isEmpty() || Integer.parseInt(OBC_VERSION_STRING.split("_")[1]) >= 17;
    private static final String NMS_PACKAGE = "net.minecraft.server" + (USE_MODERN_NMS_NAMES ? "" : "." + OBC_VERSION_STRING);

    public Class<?> getNMSClass(final String legacyName, final String modernName) throws ClassNotFoundException {
        return Class.forName(NMS_PACKAGE + "." + (USE_MODERN_NMS_NAMES ? modernName : legacyName));
    }

    public Object getServerInstance() throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        final Class<?> klass = getNMSClass("MinecraftServer", "MinecraftServer");
        final Field field = ReflectiveUtil.getFieldByType(klass, klass);
        return ReflectiveUtil.getFieldValue(null, field);
    }

    public List<ChannelFuture> getServerChannelFutures(final Object server) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        final Class<?> serverConnectionClass = getNMSClass("ServerConnection", "network.ServerConnection");
        final Field serverConnectionField = ReflectiveUtil.getFieldByType(server.getClass(), serverConnectionClass);
        final Object serverConnection = ReflectiveUtil.getFieldValue(server, serverConnectionField);

        final Field channelFuturesField = ReflectiveUtil.getFieldByType(serverConnection.getClass(), List.class);
        return ReflectiveUtil.getFieldValue(serverConnection, channelFuturesField);
    }
}