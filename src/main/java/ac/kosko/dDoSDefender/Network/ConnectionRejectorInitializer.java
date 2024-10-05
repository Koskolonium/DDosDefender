package ac.kosko.dDoSDefender.Network;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import io.netty.channel.*;
import lombok.Getter;
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
        pipeline.addFirst("connectionRejector", new ConnectionRejector(plugin));
    }
}

class ConnectionRejector extends ChannelInboundHandlerAdapter {

    private final Semaphore queueLock = new Semaphore(1);
    private final ConcurrentLinkedQueue<QueuedPacket> playerQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger packetCounter = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>();
    private final AtomicInteger packetCountInCurrentSecond = new AtomicInteger();
    private boolean sendQueueMessage = true;
    private final int maxQueueSize;
    private final int processLimit;
    private final ConcurrentHashMap<String, Long> ipLastConnectionTime = new ConcurrentHashMap<>();
    private final long RATE_LIMIT_INTERVAL_MS;

    public ConnectionRejector(JavaPlugin plugin) {
        this.maxQueueSize = plugin.getConfig().getInt("queue.size", 25);
        this.processLimit = plugin.getConfig().getInt("process.limit", 5);
        this.RATE_LIMIT_INTERVAL_MS = 5000;

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handlePlayerConnection(event);
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::processQueue, 20L, 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::resetAndWarnPacketCount, 20L, 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanupOldIpEntries, 20L, 20L);
    }

    @Getter
    private static class LoginStartData {
        private final String playerName;

        public LoginStartData(String playerName) {
            this.playerName = playerName;
        }
    }

    @Getter
    private static class QueuedPacket {
        private final PacketEvent packetEvent;
        private final int packetId;

        public QueuedPacket(PacketEvent packetEvent, int packetId) {
            this.packetEvent = packetEvent;
            this.packetId = packetId;
        }
    }

    private void handlePlayerConnection(PacketEvent event) {
        Player player = event.getPlayer();
        String ip = getPlayerIp(player);

        if (!ip.equals("127.0.0.1")) {
            long currentTime = System.currentTimeMillis();
            ipLastConnectionTime.compute(ip, (k, v) -> v == null ? currentTime : v);
        }

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
                sendQueueMessage(event.getPlayer(), MessageType.QUEUE_FULL);
                Bukkit.getLogger().info("Connection attempt from IP " + ip + " blocked due to full queue.");
            }
        } finally {
            queueLock.release();
        }
    }

    private String getPlayerIp(Player player) {
        String ipWithPort = player.getAddress().toString();
        if (ipWithPort.startsWith("/")) {
            ipWithPort = ipWithPort.substring(1);
        }
        int colonIndex = ipWithPort.indexOf(':');
        if (colonIndex != -1) {
            return ipWithPort.substring(0, colonIndex);
        }

        return ipWithPort;
    }

    private LoginStartData extractLoginStartData(PacketContainer packet) {
        if (packet.getStrings().size() == 0) {
            return null;
        }
        String playerName = packet.getStrings().read(0);
        return new LoginStartData(playerName);
    }

    private void sendQueueMessage(Player player, MessageType type) {
        try {
            PacketContainer disconnectPacket = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Server.DISCONNECT);
            String message = type == MessageType.FREQUENT_REQUEST ? "Request made too frequently. Please wait before trying again." : "Queue is full. Please try again later.";
            disconnectPacket.getChatComponents().write(0, WrappedChatComponent.fromText(message));
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, disconnectPacket);
            Bukkit.getLogger().info("Sent " + type + " message to player " + player.getName());
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
            Bukkit.getLogger().warning("High Traffic Detected: more than 150 connection requests received in 1 Second.");
            sendQueueMessage = false;
        } else {
            sendQueueMessage = true;
        }
    }

    private void cleanupOldIpEntries() {
        long currentTime = System.currentTimeMillis();
        for (String ip : ipLastConnectionTime.keySet()) {
            Long lastTime = ipLastConnectionTime.get(ip);
            if (lastTime != null && (currentTime - lastTime) > RATE_LIMIT_INTERVAL_MS) {
                ipLastConnectionTime.remove(ip);
            }
        }
    }

    private enum MessageType {
        QUEUE_FULL,
        FREQUENT_REQUEST
    }
}