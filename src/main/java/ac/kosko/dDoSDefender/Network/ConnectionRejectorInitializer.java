package ac.kosko.dDoSDefender.Network;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import io.netty.channel.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionRejectorInitializer extends ChannelInitializer<Channel> {

    private final JavaPlugin plugin;

    public ConnectionRejectorInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addFirst("connectionRejector", new ConnectionRejector(plugin)); // Adds custom handler
    }
}

class ConnectionRejector extends ChannelInboundHandlerAdapter {

    private final JavaPlugin plugin;
    private final Semaphore queueLock = new Semaphore(1);
    private final ConcurrentLinkedQueue<QueuedPacket> playerQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger packetCounter = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>();
    private final AtomicInteger packetCountInCurrentSecond = new AtomicInteger();
    private boolean sendQueueMessage = true;

    private final int maxQueueSize;
    private final int processLimit;

    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;
        this.maxQueueSize = plugin.getConfig().getInt("queue.size", 25);
        this.processLimit = plugin.getConfig().getInt("process.limit", 5);

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handlePlayerConnection(event);
            }
        });

        Bukkit.getScheduler().runTaskTimer(plugin, this::processQueue, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::resetAndWarnPacketCount, 20L, 20L);
        Bukkit.getLogger().info("ConnectionRejector initialized.");
    }

    private static class LoginStartData {
        private final String playerName;

        public LoginStartData(String playerName) {
            this.playerName = playerName;
        }

        public String getPlayerName() {
            return playerName;
        }
    }

    private static class QueuedPacket {
        private final PacketEvent packetEvent;
        private final int packetId;

        public QueuedPacket(PacketEvent packetEvent, int packetId) {
            this.packetEvent = packetEvent;
            this.packetId = packetId;
        }

        public PacketEvent getPacketEvent() {
            return packetEvent;
        }

        public int getPacketId() {
            return packetId;
        }
    }

    private void handlePlayerConnection(PacketEvent event) {
        queueLock.acquireUninterruptibly();
        try {
            if (playerQueue.size() < maxQueueSize) {
                int packetId = packetCounter.incrementAndGet();
                LoginStartData data = extractLoginStartData(event.getPacket());
                if (data != null) {
                    packetData.put(packetId, data);
                    playerQueue.add(new QueuedPacket(event, packetId));
                    event.setCancelled(true);

                    packetCountInCurrentSecond.incrementAndGet();
                }
            } else {
                event.setCancelled(true);

                if (sendQueueMessage) {
                    sendQueueMessage(event.getPlayer(), "Queue is full. Please try again later.");
                }
                Bukkit.getLogger().warning("Player connection rejected: queue is full.");
            }
        } finally {
            queueLock.release();
        }
    }

    private LoginStartData extractLoginStartData(PacketContainer packet) {
        try {
            if (packet.getStrings().size() > 0) {
                String playerName = packet.getStrings().read(0);
                return new LoginStartData(playerName);
            }
            return null;
        } catch (Exception e) {
            Bukkit.getLogger().severe("Error extracting player name from packet: " + e.getMessage());
            return null;
        }
    }

    private void sendQueueMessage(Player player, String message) {
        try {
            PacketContainer disconnectPacket = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Server.DISCONNECT);
            disconnectPacket.getChatComponents().write(0, WrappedChatComponent.fromText(message));
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, disconnectPacket);
        } catch (Exception e) {
            Bukkit.getLogger().severe("Error sending queue message: " + e.getMessage());
        }
    }

    private void processQueue() {
        queueLock.acquireUninterruptibly();
        try {
            int processedCount = 0;
            while (processedCount < processLimit && !playerQueue.isEmpty()) {
                QueuedPacket queuedPacket = playerQueue.poll();
                if (queuedPacket != null) {
                    int packetId = queuedPacket.getPacketId();
                    LoginStartData data = packetData.remove(packetId);
                    if (data != null) {
                        PacketEvent packetEvent = queuedPacket.getPacketEvent();
                        PacketContainer packet = packetEvent.getPacket();
                        packet.getStrings().write(0, data.getPlayerName());
                        try {
                            ProtocolLibrary.getProtocolManager().receiveClientPacket(packetEvent.getPlayer(), packet, false);
                            processedCount++;
                            Bukkit.getLogger().info("Processed player " + data.getPlayerName() + " with Packet ID: " + packetId);
                        } catch (Exception e) {
                            Bukkit.getLogger().warning("Failed to process packet for player " + data.getPlayerName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        } finally {
            queueLock.release();
        }
    }

    private void resetAndWarnPacketCount() {
        int count = packetCountInCurrentSecond.getAndSet(0);
        if (count > 150) {
            Bukkit.getLogger().warning("Possible DDoS Attack In Progress: more than 150 packets received in one second.");
            sendQueueMessage = false;
        } else {
            sendQueueMessage = true;
        }
    }
}