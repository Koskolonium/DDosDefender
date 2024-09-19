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

/**
 * Initializes the custom connection handler (ConnectionRejector)
 * and adds it to the Netty channel pipeline for intercepting player connections.
 */
public class ConnectionRejectorInitializer extends ChannelInitializer<Channel> {

    private final JavaPlugin plugin;

    /**
     * Constructor that accepts the plugin instance to initialize the connection rejector.
     *
     * @param plugin The plugin instance.
     */
    public ConnectionRejectorInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Adds the ConnectionRejector to the Netty channel pipeline to intercept connections.
     *
     * @param ch The Netty channel where the handler is added.
     */
    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addFirst("connectionRejector", new ConnectionRejector(plugin)); // Adds custom handler
    }
}

/**
 * Handles connection attempts, queues player connections, and limits how many players can join per second.
 */
class ConnectionRejector extends ChannelInboundHandlerAdapter {

    private static final int MAX_QUEUE_SIZE = 25;
    private static final int PROCESS_LIMIT = 5;

    private final JavaPlugin plugin;
    private final Semaphore queueLock = new Semaphore(1);
    private final ConcurrentLinkedQueue<QueuedPacket> playerQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger packetCounter = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>();
    private final AtomicInteger packetCountInCurrentSecond = new AtomicInteger();

    /**
     * Constructor that initializes the ProtocolLib packet listener and schedules tasks for the queue processing.
     *
     * @param plugin The plugin instance.
     */
    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;
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

    /**
     * Stores information extracted from Login Start packets.
     */
    private static class LoginStartData {
        private final String playerName;

        public LoginStartData(String playerName) {
            this.playerName = playerName;
        }

        public String getPlayerName() {
            return playerName;
        }
    }

    /**
     * Represents a queued connection packet along with its unique packet ID.
     */
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

    /**
     * Handles player connection requests by placing them in the queue if space is available.
     * If the queue is full, the connection is rejected with a custom message.
     *
     * @param event The packet event representing the player's connection attempt.
     */
    private void handlePlayerConnection(PacketEvent event) {
        queueLock.acquireUninterruptibly();
        try {
            if (playerQueue.size() < MAX_QUEUE_SIZE) {
                int packetId = packetCounter.incrementAndGet();
                LoginStartData data = extractLoginStartData(event.getPacket());
                if (data != null) {
                    packetData.put(packetId, data);
                    playerQueue.add(new QueuedPacket(event, packetId));
                    event.setCancelled(true);

                    packetCountInCurrentSecond.incrementAndGet();
                    Bukkit.getLogger().info("Player " + data.getPlayerName() + " added to the queue. Packet ID: " + packetId);

                    // Send a custom message telling the player they are in the queue
                    sendQueueMessage(event.getPlayer(), "You are in the queue. Please be patient.");
                } else {
                    event.setCancelled(true);
                    Bukkit.getLogger().warning("Failed to extract player data from the Login Start packet.");
                }
            } else {
                event.setCancelled(true);

                // Send custom message when the queue is full
                sendQueueMessage(event.getPlayer(), "Queue is full. Please try again later.");
                Bukkit.getLogger().warning("Player connection rejected: queue is full.");
            }
        } finally {
            queueLock.release();
        }
    }

    /**
     * Extracts the player's name from the Login Start packet.
     *
     * @param packet The Login Start packet.
     * @return A LoginStartData object containing the player's name, or null if extraction fails.
     */
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

    /**
     * Sends a disconnect message to the player during the login process.
     *
     * @param player The player to send the message to.
     * @param message The message to display when the player is disconnected.
     */
    private void sendQueueMessage(Player player, String message) {
        try {
            PacketContainer disconnectPacket = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Server.DISCONNECT);
            disconnectPacket.getChatComponents().write(0, WrappedChatComponent.fromText(message));
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, disconnectPacket);
        } catch (Exception e) {
            Bukkit.getLogger().severe("Error sending queue message: " + e.getMessage());
        }
    }

    /**
     * Processes the player connection queue and sends packets for further processing.
     * Processes a maximum of 5 player connections per second.
     */
    private void processQueue() {
        queueLock.acquireUninterruptibly();
        try {
            int processedCount = 0;
            while (processedCount < PROCESS_LIMIT && !playerQueue.isEmpty()) {
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

    /**
     * Resets the packet count every second and logs a warning if more than 150 packets were received.
     */
    private void resetAndWarnPacketCount() {
        int count = packetCountInCurrentSecond.getAndSet(0);
        if (count > 150) {
            Bukkit.getLogger().warning("Possible DDoS Attack In Progress: more than 150 packets received in one second.");
        }
    }
}