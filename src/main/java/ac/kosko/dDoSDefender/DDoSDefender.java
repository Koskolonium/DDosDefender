package ac.kosko.dDoSDefender;

import ac.kosko.dDoSDefender.Network.ConnectionRejectorInitializer;
import ac.kosko.dDoSDefender.NetworkUtils.ConnectionPipelineInjector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class DDoSDefender extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        ConnectionPipelineInjector.registerChannelInitializer("ConnectionRejector", new ConnectionRejectorInitializer(this));
        ConnectionPipelineInjector.inject();
        Bukkit.getLogger().info("DDoSDefender has been Enabled.");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        Bukkit.getLogger().info("DDoSDefender has been disabled.");
    }
}