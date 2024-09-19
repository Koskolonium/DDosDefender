package ac.kosko.dDoSDefender;

import ac.kosko.dDoSDefender.Network.ConnectionRejectorInitializer;
import ac.kosko.dDoSDefender.NetworkUtils.ConnectionPipelineInjector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class DDoSDefender extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConnectionPipelineInjector.registerChannelInitializer("ConnectionRejector", new ConnectionRejectorInitializer(this));
        ConnectionPipelineInjector.inject();
        Bukkit.getLogger().info("=====================================");
        Bukkit.getLogger().info("   Thank you for using DDoSDefender!  ");
        Bukkit.getLogger().info("   Your server's Network is now       ");
        Bukkit.getLogger().info("   under enhanced protection.         ");
        Bukkit.getLogger().info("=====================================");
    }

    @Override
    public void onDisable() {
        Bukkit.getLogger().info("=====================================");
        Bukkit.getLogger().info("   DDoSDefender Plugin is shutting     ");
        Bukkit.getLogger().info("   down. Your server's protection      ");
        Bukkit.getLogger().info("   will be temporarily paused.         ");
        Bukkit.getLogger().info("   Thank you for using DDoSDefender!   ");
        Bukkit.getLogger().info("=====================================");
    }
}