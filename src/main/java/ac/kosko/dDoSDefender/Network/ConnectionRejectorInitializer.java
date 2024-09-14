package ac.kosko.dDoSDefender.Network;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import io.netty.channel.*;
import org.bukkit.Bukkit;
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

    private static final int MAX_QUEUE_SIZE = 9; // Maximum number of player connections allowed in the queue.
    private static final int PROCESS_LIMIT = 3; // Limit of how many player connections are processed per second.

    private final JavaPlugin plugin; // Reference to the plugin instance.
    private final Semaphore queueLock = new Semaphore(1); // Ensures thread-safe access to the playerQueue.
    private final ConcurrentLinkedQueue<QueuedPacket> playerQueue = new ConcurrentLinkedQueue<>(); // Queue for storing player connection requests.
    private final AtomicInteger packetCounter = new AtomicInteger(); // Tracks the number of processed packets.
    private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>(); // Stores data of queued Login Start packets.
    private final AtomicInteger packetCountInCurrentSecond = new AtomicInteger(); // Counts packets received in the current second.

    /**
     * Constructor that initializes the ProtocolLib packet listener and schedules tasks for the queue processing.
     *
     * @param plugin The plugin instance.
     */
    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;

        // Listener for the Login Start packet using ProtocolLib.
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handlePlayerConnection(event); // Process incoming connection packets.
            }
        });

        // Schedule a task that processes queued player connections every second.
        Bukkit.getScheduler().runTaskTimer(plugin, this::processQueue, 20L, 20L);

        // Schedule a task that resets the packet count every second and logs a warning if too many packets are received.
        Bukkit.getScheduler().runTaskTimer(plugin, this::resetAndWarnPacketCount, 20L, 20L);
    }

    /**
     * Stores information extracted from Login Start packets.
     */
    private static class LoginStartData {
        private final String playerName; // The player's name from the Login Start packet.

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
        private final PacketEvent packetEvent; // The packet event representing a player's login attempt.
        private final int packetId; // A unique ID for each packet.

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
     * If the queue is full, the connection is rejected.
     *
     * @param event The packet event representing the player's connection attempt.
     */
    private void handlePlayerConnection(PacketEvent event) {
        queueLock.acquireUninterruptibly(); // Ensure exclusive access to the queue.
        try {
            if (playerQueue.size() < MAX_QUEUE_SIZE) { // Check if there's space in the queue.
                int packetId = packetCounter.incrementAndGet(); // Generate a unique packet ID.
                LoginStartData data = extractLoginStartData(event.getPacket()); // Extract the player's data from the packet.
                if (data != null) {
                    packetData.put(packetId, data); // Store the extracted data in the map.
                    playerQueue.add(new QueuedPacket(event, packetId)); // Add the packet to the queue.
                    event.setCancelled(true); // Cancel the packet to hold the connection until processed.

                    // Increment the packet count for the current second.
                    packetCountInCurrentSecond.incrementAndGet();
                } else {
                    event.setCancelled(true); // Cancel the packet if data extraction fails.
                }
            } else {
                event.setCancelled(true); // Cancel the packet if the queue is full.
            }
        } finally {
            queueLock.release(); // Release the lock to allow other threads to access the queue.
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
            // Extract the player's name from the Login Start packet.
            if (packet.getStrings().size() > 0) {
                String playerName = packet.getStrings().read(0);
                return new LoginStartData(playerName);
            }
            return null;
        } catch (Exception e) {
            return null; // Return null if extraction fails.
        }
    }

    /**
     * Processes the player connection queue and sends packets for further processing.
     * Processes a maximum of 3 player connections per second.
     */
    private void processQueue() {
        queueLock.acquireUninterruptibly(); // Ensure exclusive access to the queue.
        try {
            int processedCount = 0; // Tracks the number of processed packets.
            while (processedCount < PROCESS_LIMIT && !playerQueue.isEmpty()) { // Process up to PROCESS_LIMIT packets.
                QueuedPacket queuedPacket = playerQueue.poll(); // Retrieve the next packet from the queue.
                if (queuedPacket != null) {
                    int packetId = queuedPacket.getPacketId();
                    LoginStartData data = packetData.remove(packetId); // Remove the corresponding data from the map.
                    if (data != null) {
                        PacketEvent packetEvent = queuedPacket.getPacketEvent();
                        PacketContainer packet = packetEvent.getPacket();
                        packet.getStrings().write(0, data.getPlayerName()); // Re-insert the player name into the packet.

                        // Process the packet using ProtocolLib to allow the player to join the server.
                        try {
                            ProtocolLibrary.getProtocolManager().receiveClientPacket(packetEvent.getPlayer(), packet, false);
                            processedCount++;
                        } catch (Exception e) {
                            Bukkit.getLogger().warning("Failed to process packet: " + e.getMessage()); // Log a warning if packet processing fails.
                        }
                    }
                }
            }
        } finally {
            queueLock.release(); // Release the lock to allow other threads to access the queue.
        }
    }

    /**
     * Resets the packet count every second and logs a warning if more than 50 packets were received.
     */
    private void resetAndWarnPacketCount() {
        int count = packetCountInCurrentSecond.getAndSet(0); // Get and reset the packet count.
        if (count > 50) {
            Bukkit.getLogger().warning("More than 50 Login Start packets received in the last second.");
        }
    }
}