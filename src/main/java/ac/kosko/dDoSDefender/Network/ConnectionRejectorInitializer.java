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
    private final AtomicInteger packetCounter = new AtomicInteger(0); // Tracks the number of processed packets.
    private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>(); // Stores data of queued Login Start packets.
    private final AtomicInteger loginStartCount = new AtomicInteger(0); // Tracks how many Login Start packets are received per second.

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

        // Schedule a task that resets the loginStartCount and warns if too many Login Start packets are received.
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
        queueLock.acquireUninterruptibly();
        try {
            if (playerQueue.size() < MAX_QUEUE_SIZE) {
                int packetId = packetCounter.incrementAndGet(); // Generate a unique packet ID.
                LoginStartData data = extractLoginStartData(event.getPacket()); // Extract the player's data.
                if (data != null) {
                    packetData.put(packetId, data); // Store the extracted data.
                    playerQueue.add(new QueuedPacket(event, packetId)); // Add the packet to the queue.
                    event.setCancelled(true); // Hold the connection until processed.
                } else {
                    event.setCancelled(true); // Cancel the connection if data extraction fails.
                }

                // Increment the loginStartCount and check for threshold warnings.
                int currentCount = loginStartCount.incrementAndGet();
                if (currentCount > 50) {
                    Bukkit.getLogger().warning("More than 50 Login Start packets received in the last second."); // Warn if more than 50 packets received in a second.
                }
            } else {
                event.setCancelled(true); // Cancel the connection if the queue is full.
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
            // Extract the player's name from the Login Start packet.
            if (packet.getStrings().size() > 0) {
                String playerName = packet.getStrings().read(0);
                return new LoginStartData(playerName);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Processes the player connection queue and sends packets for further processing.
     * Processes a maximum of 2 player connections per second.
     */
    private void processQueue() {
        queueLock.acquireUninterruptibly();
        try {
            int processedCount = 0; // Tracks the number of processed packets.
            while (processedCount < PROCESS_LIMIT && !playerQueue.isEmpty()) {
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
                        } catch (Exception ignored) {
                            // In case of exceptions, the packet processing is skipped.
                        }
                    }
                }
            }
        } finally {
            queueLock.release();
        }
    }

    /**
     * Resets the loginStartCount every second and logs a warning if more than 50 packets were received.
     */
    private void resetAndWarnPacketCount() {
        loginStartCount.set(0); // Reset the counter every second.
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx); // Called when a new channel is registered.
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx); // Called when a channel is unregistered.
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx); // Called when a channel becomes active.
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx); // Called when a channel becomes inactive.
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause); // Handle exceptions in the channel.
    }
}