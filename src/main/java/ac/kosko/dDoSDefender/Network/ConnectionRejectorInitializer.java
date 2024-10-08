package ac.kosko.dDoSDefender.Network;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import lombok.Getter;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private final ConcurrentLinkedQueue<QueuedPacket> playerQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger packetCounter = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>();
    private final AtomicInteger packetCountInCurrentSecond = new AtomicInteger();
    private boolean sendQueueMessage = true;
    private final int maxQueueSize;
    private final int processLimit;
    private final ConcurrentHashMap<String, Long> ipLastConnectionTime = new ConcurrentHashMap<>();
    private final long RATE_LIMIT_INTERVAL_MS;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<String, Boolean> verifiedPlayerNames = new ConcurrentHashMap<>();
    private final File verifiedNamesFile;
    private final ConcurrentHashMap<String, Boolean> invalidatedPlayerNames = new ConcurrentHashMap<>();
    private final File invalidatedNamesFile;
    private final AtomicInteger verificationCounter = new AtomicInteger(0);
    private final int VERIFICATION_LIMIT_PER_MINUTE = 200;

    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;
        this.maxQueueSize = plugin.getConfig().getInt("queue.size", 40);
        this.processLimit = plugin.getConfig().getInt("process.limit", 8);
        this.RATE_LIMIT_INTERVAL_MS = 5000;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
        this.verifiedNamesFile = new File(plugin.getDataFolder(), "verified_players.txt");
        if (!verifiedNamesFile.exists()) {
            try {
                if (verifiedNamesFile.createNewFile()) {
                    Bukkit.getLogger().info("Created verified_players.txt");
                }
            } catch (IOException e) {
                Bukkit.getLogger().severe("Failed to create verified_players.txt: " + e.getMessage());
            }
        }

        this.invalidatedNamesFile = new File(plugin.getDataFolder(), "invalidated_players.txt");
        if (!invalidatedNamesFile.exists()) {
            try {
                if (invalidatedNamesFile.createNewFile()) {
                    Bukkit.getLogger().info("Created invalidated_players.txt");
                }
            } catch (IOException e) {
                Bukkit.getLogger().severe("Failed to create invalidated_players.txt: " + e.getMessage());
            }
        }

        loadVerifiedPlayerNames();
        loadInvalidatedPlayerNames();

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handlePlayerConnection(event);
            }
        });

        Bukkit.getScheduler().runTaskTimer(plugin, this::processQueue, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::resetVerificationCounter, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::resetAndWarnPacketCount, 20L, 20L);
    }

    @Getter
    private static class LoginStartData {
        private final String playerName;
        private final UUID playerUUID;

        public LoginStartData(String playerName, UUID playerUUID) {
            this.playerName = playerName;
            this.playerUUID = playerUUID;
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

    private String getPlayerIp(Player player) {
        String ipWithPort = Objects.requireNonNull(player.getAddress()).toString();
        if (ipWithPort.startsWith("/")) {
            ipWithPort = ipWithPort.substring(1);
        }
        int colonIndex = ipWithPort.indexOf(':');
        if (colonIndex != -1) {
            return ipWithPort.substring(0, colonIndex);
        }
        return ipWithPort;
    }

    private void handlePlayerConnection(PacketEvent event) {
        Player player = event.getPlayer();
        String ip = getPlayerIp(player);
        if (!ip.equals("127.0.0.1")) {
            long currentTime = System.currentTimeMillis();
            Long lastTime = ipLastConnectionTime.get(ip);
            if (lastTime != null && (currentTime - lastTime) < RATE_LIMIT_INTERVAL_MS) {
                event.setCancelled(true);
                sendQueueMessage(event.getPlayer(), MessageType.FREQUENT_REQUEST);
                return;
            }
            ipLastConnectionTime.put(ip, currentTime);
        }

        String playerName = player.getName().toLowerCase();

        if (verifiedPlayerNames.containsKey(playerName)) {
            enqueuePlayer(event, new LoginStartData(player.getName(), null));
            return;
        }

        if (invalidatedPlayerNames.containsKey(playerName)) {
            event.setCancelled(true);
            sendQueueMessage(event.getPlayer(), MessageType.BOT_DETECTED);
            return;
        }

        if (verificationCounter.get() >= VERIFICATION_LIMIT_PER_MINUTE) {
            event.setCancelled(true);
            sendQueueMessage(event.getPlayer(), MessageType.VERIFICATION_LIMIT_REACHED);
            return;
        }

        LoginStartData data = extractLoginStartData(event.getPacket());
        if (data != null) {
            verifyPlayerUUID(data, event);
        }
    }

    private LoginStartData extractLoginStartData(PacketContainer packet) {
        try {
            if (packet.getStrings().size() == 0) {
                return null;
            }
            String playerName = packet.getStrings().read(0);
            UUID playerUUID = packet.getUUIDs().size() > 0 ? packet.getUUIDs().read(0) : null;
            return new LoginStartData(playerName, playerUUID);
        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to extract login start data: " + e.getMessage());
            return null;
        }
    }

    private void verifyPlayerUUID(LoginStartData data, PacketEvent event) {
        String playerName = data.getPlayerName();
        UUID packetUUID = data.getPlayerUUID();
        String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String body = response.body().string();
                if (body.isEmpty()) {
                    event.setCancelled(true);
                    sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION);
                    addInvalidatedPlayer(playerName);
                    return;
                }

                MojangResponse mojangResponse = gson.fromJson(body, MojangResponse.class);
                if (mojangResponse != null && mojangResponse.getId() != null) {
                    UUID fetchedUUID = UUID.fromString(
                            mojangResponse.getId().replaceFirst(
                                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                                    "$1-$2-$3-$4-$5"
                            )
                    );

                    if (packetUUID != null) {
                        if (fetchedUUID.equals(packetUUID)) {
                            enqueuePlayer(event, data);
                            addVerifiedPlayer(playerName);
                            verificationCounter.incrementAndGet();
                        } else {
                            event.setCancelled(true);
                            sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION);
                            addInvalidatedPlayer(playerName);
                        }
                    } else {
                        enqueuePlayer(event, data);
                        addVerifiedPlayer(playerName);
                        verificationCounter.incrementAndGet();
                    }
                } else {
                    event.setCancelled(true);
                    sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION);
                    addInvalidatedPlayer(playerName);
                }
            } else if (response.code() == 204 || response.code() == 404) {
                event.setCancelled(true);
                sendQueueMessage(event.getPlayer(), MessageType.BOT_DETECTED);
                addInvalidatedPlayer(playerName);
            } else {
                event.setCancelled(true);
                sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION);
                addInvalidatedPlayer(playerName);
            }
        } catch (IOException e) {
            event.setCancelled(true);
            sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION);
            addInvalidatedPlayer(playerName);
        }
    }

    private void enqueuePlayer(PacketEvent event, LoginStartData data) {
        if (playerQueue.size() < maxQueueSize) {
            int packetId = packetCounter.incrementAndGet();
            packetData.put(packetId, data);
            playerQueue.add(new QueuedPacket(event, packetId));
            event.setCancelled(true);
            packetCountInCurrentSecond.incrementAndGet();
        } else {
            event.setCancelled(true);
            sendQueueMessage(event.getPlayer(), MessageType.QUEUE_FULL);
        }
    }

    private void sendQueueMessage(Player player, MessageType type) {
        try {
            PacketContainer disconnectPacket = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Server.DISCONNECT);
            String message;
            switch (type) {
                case FREQUENT_REQUEST:
                    message = "Request made too frequently. Please wait before trying again.";
                    break;
                case QUEUE_FULL:
                    message = "Queue is full. Please try again later.";
                    break;
                case BOT_DETECTED:
                    message = "Bot detected. Connection rejected.";
                    break;
                case FAILED_VERIFICATION:
                    message = "Failed to verify your account. Connection rejected.";
                    break;
                case VERIFICATION_LIMIT_REACHED:
                    message = "Connection could not be verified. Please try again.";
                    break;
                default:
                    message = "Connection rejected.";
                    break;
            }
            disconnectPacket.getChatComponents().write(0, WrappedChatComponent.fromText(message));
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, disconnectPacket);
        } catch (Exception e) {
            Bukkit.getLogger().severe("Error sending queue message: " + e.getMessage());
        }
    }

    private void processQueue() {
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
    }

    private void resetVerificationCounter() {
        verificationCounter.set(0);
        Bukkit.getLogger().info("Verification counter reset.");
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

    private enum MessageType {
        QUEUE_FULL,
        FREQUENT_REQUEST,
        BOT_DETECTED,
        FAILED_VERIFICATION,
        VERIFICATION_LIMIT_REACHED
    }

    private static class MojangResponse {
        private String id;
        private String name;
        private Boolean legacy;
        private Boolean demo;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Boolean getLegacy() {
            return legacy;
        }

        public Boolean getDemo() {
            return demo;
        }
    }

    private void loadVerifiedPlayerNames() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(verifiedNamesFile.getPath()));
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    verifiedPlayerNames.put(line.trim().toLowerCase(), true);
                }
            }
            Bukkit.getLogger().info("Loaded " + verifiedPlayerNames.size() + " verified player names.");
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to load verified player names: " + e.getMessage());
        }
    }

    private void loadInvalidatedPlayerNames() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(invalidatedNamesFile.getPath()));
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    invalidatedPlayerNames.put(line.trim().toLowerCase(), true);
                }
            }
            Bukkit.getLogger().info("Loaded " + invalidatedPlayerNames.size() + " invalidated player names.");
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to load invalidated player names: " + e.getMessage());
        }
    }

    private void addVerifiedPlayer(String playerName) {
        String lowerCaseName = playerName.toLowerCase();
        if (verifiedPlayerNames.putIfAbsent(lowerCaseName, true) == null) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(verifiedNamesFile, true))) {
                writer.write(lowerCaseName);
                writer.newLine();
                Bukkit.getLogger().info("Added verified player: " + playerName);
            } catch (IOException e) {
                Bukkit.getLogger().severe("Failed to add verified player " + playerName + ": " + e.getMessage());
            }
        }
    }

    private void addInvalidatedPlayer(String playerName) {
        String lowerCaseName = playerName.toLowerCase();
        if (invalidatedPlayerNames.putIfAbsent(lowerCaseName, true) == null) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(invalidatedNamesFile, true))) {
                writer.write(lowerCaseName);
                writer.newLine();
                Bukkit.getLogger().info("Added invalidated player: " + playerName);
            } catch (IOException e) {
                Bukkit.getLogger().severe("Failed to add invalidated player " + playerName + ": " + e.getMessage());
            }
        }
    }
}