package ac.kosko.dDoSDefender;

import ac.kosko.dDoSDefender.Network.ConnectionRejector;
import ac.kosko.dDoSDefender.NetworkUtils.ConnectionPipelineInjector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class DDoSDefender extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        ConnectionRejector.setPlugin(this);
        ConnectionPipelineInjector.inject(); // Call the inject method
        Bukkit.getLogger().info("DDoSDefender has been Enabled.");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        ConnectionPipelineInjector.remove();
        Bukkit.getLogger().info("DDoSDefender has been disabled.");
    }
}