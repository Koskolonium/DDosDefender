package ac.kosko.dDoSDefender;

import ac.kosko.dDoSDefender.Network.ConnectionRejectorInitializer;
import ac.kosko.dDoSDefender.NetworkUtils.ConnectionPipelineInjector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The main class for the DDoSDefender plugin.
 * Responsible for enabling and disabling the plugin,
 * and initializing network defense mechanisms.
 */
public final class DDoSDefender extends JavaPlugin {

    @Override
    public void onEnable() {
        // Register the custom network handler for rejecting connections
        // and inject it into the server's network pipeline.
        ConnectionPipelineInjector.registerChannelInitializer("ConnectionRejector", new ConnectionRejectorInitializer(this));
        ConnectionPipelineInjector.inject();

        // Log a message indicating that the plugin is now active.
        Bukkit.getLogger().info("DDoSDefender has been Enabled.");
    }

    @Override
    public void onDisable() {
        // Log a message indicating that the plugin is being shut down.
        Bukkit.getLogger().info("DDoSDefender has been disabled.");
    }
}