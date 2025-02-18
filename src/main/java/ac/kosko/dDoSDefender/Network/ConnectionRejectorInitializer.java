package ac.kosko.dDoSDefender.Network;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionRejectorInitializer extends ChannelInitializer<Channel> {

    private final JavaPlugin plugin;

    public ConnectionRejectorInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addFirst("connectionRejector", new ConnectionRejector(plugin));
    }

    static class ConnectionRejector extends ChannelInboundHandlerAdapter {
        @Setter
        @Getter
        private final JavaPlugin plugin;
        private final ConcurrentLinkedQueue<QueuedPacket> playerQueue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger packetCounter = new AtomicInteger();
        private final ConcurrentHashMap<Integer, LoginStartData> packetData = new ConcurrentHashMap<>();
        private final AtomicInteger packetCountInCurrentSecond = new AtomicInteger();
        private boolean sendQueueMessage = true;
        private final int maxQueueSize;
        private final int processLimit;
        private final long blockDurationMs; // Duration for blocking IPs in milliseconds
        private final ConcurrentHashMap<String, Long> blockedNetworks = new ConcurrentHashMap<>();
        private final OkHttpClient httpClient;
        private final Gson gson;
        private final ConcurrentHashMap<String, Boolean> verifiedPlayerNames = new ConcurrentHashMap<>();
        private final File verifiedNamesFile;
        private final ConcurrentHashMap<String, Boolean> invalidatedPlayerNames = new ConcurrentHashMap<>();
        private final File invalidatedNamesFile;
        private final File blacklistedIPsFile;
        private final AtomicInteger verificationCounter = new AtomicInteger();
        private final AtomicInteger invalidationCounter = new AtomicInteger();
        private BufferedWriter verifiedWriter;
        private BufferedWriter invalidatedWriter;
        private static final long TICK_INTERVAL = 20L;
        private final boolean rateLimitIps;
        private final ConcurrentHashMap<String, Long> blacklistedIPs = new ConcurrentHashMap<>();
        private final ScheduledExecutorService rateLimitExecutor = Executors.newScheduledThreadPool(1);
        private final ConcurrentHashMap<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
        private final Set<String> additionalIgnoredIPs = new HashSet<>();

        public ConnectionRejector(JavaPlugin plugin) {
            this.plugin = plugin;
            this.maxQueueSize = plugin.getConfig().getInt("queue.size", 40);
            this.processLimit = plugin.getConfig().getInt("process.limit", 8);
            this.blockDurationMs = plugin.getConfig().getInt("ratelimit_duration_seconds", 15) * 1000L;
            this.rateLimitIps = plugin.getConfig().getBoolean("rate-limit_ips", true);
            List<String> ignoredIpsFromConfig = plugin.getConfig().getStringList("ignored_ips");
            additionalIgnoredIPs.addAll(ignoredIpsFromConfig);
            this.httpClient = new OkHttpClient.Builder()
                    .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            this.gson = new Gson();
            this.verifiedNamesFile = new File(plugin.getDataFolder(), "verified_players.txt");
            this.invalidatedNamesFile = new File(plugin.getDataFolder(), "invalidated_players.txt");
            this.blacklistedIPsFile = new File(plugin.getDataFolder(), "BlackListedIPs.txt");
            initializeBlacklistedIPsFile();
            loadBlacklistedIPs();
            initializeFiles();
            loadPlayerNames();
            initializeWriters();
            setupPacketListener();
            schedulePeriodicTasks();
        }

        private void initializeFiles() {
            try {
                if (!verifiedNamesFile.exists()) {
                    verifiedNamesFile.createNewFile();
                }

                if (!invalidatedNamesFile.exists()) {
                    invalidatedNamesFile.createNewFile();
                }
            } catch (IOException e) {
            }
        }

        private void initializeBlacklistedIPsFile() {
            try {
                if (!blacklistedIPsFile.exists()) {
                    blacklistedIPsFile.createNewFile();
                }
            } catch (IOException e) {
                Bukkit.getLogger().severe("DDoSDefender: Failed to initialize BlackListedIPs file: " + e.getMessage());
            }
        }
        private void loadBlacklistedIPs() {
            try (BufferedReader reader = Files.newBufferedReader(blacklistedIPsFile.toPath())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        blacklistedIPs.put(trimmed, System.currentTimeMillis());
                    }
                }
            } catch (IOException e) {
                Bukkit.getLogger().warning("DDoSDefender: Failed to load blacklisted IPs: " + e.getMessage());
            }
        }

        private void monitorIp(String ip) {
            connectionCounts.putIfAbsent(ip, new AtomicInteger(0));
            AtomicInteger count = connectionCounts.get(ip);

            count.incrementAndGet();

            rateLimitExecutor.schedule(() -> {
                int currentCount = count.get();
                if (currentCount > 5) {
                    blacklistedIPs.put(ip, System.currentTimeMillis());
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(blacklistedIPsFile, true))) {
                        writer.write(ip);
                        writer.newLine();
                    } catch (IOException e) {
                        Bukkit.getLogger().severe("DDoSDefender: Failed to write to BlackListedIPs file: " + e.getMessage());
                    }
                }
                connectionCounts.remove(ip);
            }, 2000, TimeUnit.MILLISECONDS);
        }

        private void initializeWriters() {
            try {
                this.verifiedWriter = new BufferedWriter(new FileWriter(verifiedNamesFile, true));
                this.invalidatedWriter = new BufferedWriter(new FileWriter(invalidatedNamesFile, true));
            } catch (IOException e) {
                Bukkit.getLogger().severe("DDoSDefender: Failed to initialize file writers: " + e.getMessage());
            }
        }

        private void loadPlayerNames() {
            loadNamesFromFile(verifiedNamesFile, verifiedPlayerNames, "verified");
            loadNamesFromFile(invalidatedNamesFile, invalidatedPlayerNames, "invalidated");
        }

        private void loadNamesFromFile(File file, ConcurrentHashMap<String, Boolean> map, String nameType) {
            try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        map.put(trimmed, true); // Keep original casing
                    }
                }
            } catch (IOException e) {
                Bukkit.getLogger().warning("DDoSDefender: Failed to load " + nameType + " player names: " + e.getMessage());
            }
        }

        private void setupPacketListener() {
            ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Login.Client.START) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    handlePlayerConnection(event);
                }
            });
        }

        private void schedulePeriodicTasks() {
            plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::processQueue, TICK_INTERVAL, TICK_INTERVAL);
            plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::resetVerificationCounter, 1200L, 1200L);
            plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::resetAndWarnPacketCount, TICK_INTERVAL, TICK_INTERVAL);
        }

        private static final Set<String> IGNORED_IP_ADDRESSES = Set.of(
                "198.178.119.0",
                "104.234.6.0",
                "51.161.19.224",
                "51.222.93.0",
                "51.222.93.32",
                "51.222.92.224",
                "51.178.221.0",
                "51.77.31.32",
                "51.89.81.0",
                "51.89.81.32",
                "51.195.87.96",
                "51.195.87.128",
                "51.195.52.0",
                "141.95.23.0",
                "141.95.62.224",
                "146.59.66.0",
                "146.59.66.32",
                "146.59.65.224",
                "51.81.4.128",
                "51.222.55.28",
                "149.56.152.184",
                "158.69.58.208",
                "51.79.61.228",
                "51.178.244.40",
                "178.32.145.164",
                "5.196.219.36",
                "51.89.127.36",
                "54.36.236.48",
                "54.38.216.200",
                "51.75.85.108",
                "51.38.153.44",
                "51.83.245.80",
                "135.125.217.68",
                "37.19.206.89",
                "212.102.60.221",
                "149.102.229.10"
        );
        private boolean isIgnoredIp(String ip) {
            return IGNORED_IP_ADDRESSES.contains(ip) || additionalIgnoredIPs.contains(ip);
        }

        private void handlePlayerConnection(PacketEvent event) {
            Player player = event.getPlayer();
            String fullIp = getPlayerIp(player);

            if (!isIgnoredIp(fullIp)) {
                if (blacklistedIPs.containsKey(fullIp)) {
                    event.setCancelled(true);
                    return;
                }
                monitorIp(fullIp);
            }

            LoginStartData loginStartData = extractLoginStartData(event.getPacket());
            if (loginStartData == null) {
                event.setCancelled(true);
                Bukkit.getLogger().warning("DDoSDefender: Failed to extract login start data. Cancelling connection for player: " + player.getName());
                return;
            }
            String playerName = loginStartData.getPlayerName();

            if (rateLimitIps) {
                if (isIgnoredIp(fullIp)) {
                } else {
                    String network = getNetworkPortion(fullIp);

                    if (isNetworkBlocked(network)) {
                        event.setCancelled(true);
                        long remainingSeconds = getRemainingBlockTime(network);
                        sendQueueMessage(player, MessageType.FREQUENT_REQUEST, remainingSeconds);
                        return;
                    } else {
                        blockNetworkTemporarily(network);
                    }
                }
            }

            Bukkit.getLogger().info("DDoSDefender: Handling connection for player: " + playerName);

            if (!isValidPlayerName(playerName)) {
                event.setCancelled(true);
                sendQueueMessage(player, MessageType.FAILED_VERIFICATION, 0);
                return;
            }

            if (verifiedPlayerNames.containsKey(playerName)) {
                enqueuePlayer(event, loginStartData);
                return;
            }

            if (invalidatedPlayerNames.containsKey(playerName)) {
                event.setCancelled(true);
                sendQueueMessage(player, MessageType.BOT_DETECTED, 0);
                return;
            }

            int VERIFICATION_LIMIT_PER_MINUTE = 200;
            if (verificationCounter.get() >= VERIFICATION_LIMIT_PER_MINUTE) {
                event.setCancelled(true);
                sendQueueMessage(player, MessageType.VERIFICATION_LIMIT_REACHED, 0);
                return;
            }

            if (!verifiedPlayerNames.containsKey(playerName) && !invalidatedPlayerNames.containsKey(playerName) && verificationCounter.get() < VERIFICATION_LIMIT_PER_MINUTE) {
                verifyPlayerUUID(loginStartData, event);
                return;
            }

            enqueuePlayer(event, loginStartData);
        }

        private boolean isValidPlayerName(String playerName) {
            if (playerName == null || playerName.isEmpty()) {
                return false;
            }
            boolean isValid = playerName.matches("^[a-zA-Z0-9_]{1,16}$");
            return isValid;
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
                return null;
            }
        }

        private void verifyPlayerUUID(LoginStartData data, PacketEvent event) {
            String playerName = data.getPlayerName();
            UUID packetUUID = data.getPlayerUUID();
            String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            Request request = new Request.Builder().url(url).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = Objects.requireNonNull(response.body()).string();
                    if (body.isEmpty()) {
                        event.setCancelled(true);
                        sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION, 0);
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
                                sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION, 0);
                                addInvalidatedPlayer(playerName);
                            }
                        } else {
                            enqueuePlayer(event, data);
                            addVerifiedPlayer(playerName);
                            verificationCounter.incrementAndGet();
                        }
                    } else {
                        event.setCancelled(true);
                        sendQueueMessage(event.getPlayer(), MessageType.FAILED_VERIFICATION, 0);
                        addInvalidatedPlayer(playerName);
                    }
                } else if (response.code() == 204 || response.code() == 404) {
                    event.setCancelled(true);
                    sendQueueMessage(event.getPlayer(), MessageType.BOT_DETECTED, 0);
                    addInvalidatedPlayer(playerName);
                } else {
                    enqueuePlayer(event, data);
                }
            } catch (IOException e) {
                enqueuePlayer(event, data);
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
                sendQueueMessage(event.getPlayer(), MessageType.QUEUE_FULL, 0);
            }
        }

        private void sendQueueMessage(Player player, MessageType type, long remainingSeconds) {
            if (!sendQueueMessage) return;

            try {
                PacketContainer disconnectPacket = ProtocolLibrary.getProtocolManager()
                        .createPacket(PacketType.Login.Server.DISCONNECT);
                String message = getMessageByType(type, remainingSeconds);
                disconnectPacket.getChatComponents().write(0, WrappedChatComponent.fromText(message));
                ProtocolLibrary.getProtocolManager().sendServerPacket(player, disconnectPacket);
            } catch (Exception e) {
                Bukkit.getLogger().severe("DDoSDefender: Error sending queue message: " + e.getMessage());
            }
        }

        private String getMessageByType(MessageType type, long remainingSeconds) {
            String prefix = ChatColor.BLUE + "[DDoSDefender] " + ChatColor.RESET;
            return switch (type) {
                case FREQUENT_REQUEST ->
                        prefix + ChatColor.WHITE + "Your network is being rate limited. Please wait "
                                + ChatColor.WHITE + remainingSeconds + " seconds " + ChatColor.WHITE + "before trying again.";
                case QUEUE_FULL ->
                        prefix + ChatColor.WHITE + "The queue is currently full. Please try again later.";
                case BOT_DETECTED ->
                        prefix + ChatColor.WHITE + "Bot activity detected. Your connection has been rejected.";
                case FAILED_VERIFICATION ->
                        prefix + ChatColor.WHITE + "Failed to verify your account. Connection has been rejected.";
                case VERIFICATION_LIMIT_REACHED ->
                        prefix + ChatColor.WHITE + "Verification limit reached. Please try connecting again shortly.";
                default -> prefix + ChatColor.WHITE + "Connection rejected.";
            };
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
                            Bukkit.getLogger().warning("DDoSDefender: Failed to process packet for player " + data.getPlayerName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        private void resetVerificationCounter() {
            verificationCounter.set(0);
        }

        private void resetAndWarnPacketCount() {
            int count = packetCountInCurrentSecond.getAndSet(0);
            if (count > 150) {
                Bukkit.getLogger().warning("⚠️ DDoSDefender: High Traffic Detected!! ⚠️");
                sendQueueMessage = false;
            } else {
                sendQueueMessage = true;
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

        private String getNetworkPortion(String ip) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 3) {
                return parts[0] + "." + parts[1] + "." + parts[2];
            }
            return ip;
        }

        private boolean isNetworkBlocked(String network) {
            Long unblockTime = blockedNetworks.get(network);
            if (unblockTime == null) {
                return false;
            }
            if (System.currentTimeMillis() >= unblockTime) {
                blockedNetworks.remove(network);
                return false;
            }
            return true;
        }

        private long getRemainingBlockTime(String network) {
            Long unblockTime = blockedNetworks.get(network);
            if (unblockTime == null) {
                return 0;
            }
            long remaining = Math.round((unblockTime - System.currentTimeMillis()) / 1000.0);
            return remaining > 0 ? remaining : 0;
        }

        private void blockNetworkTemporarily(String network) {
            long unblockTime = System.currentTimeMillis() + blockDurationMs;
            blockedNetworks.put(network, unblockTime);
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> blockedNetworks.remove(network), 300L);
        }

        private void addVerifiedPlayer(String playerName) {
            if (verifiedPlayerNames.putIfAbsent(playerName, true) == null) {
                try {
                    verifiedWriter.write(playerName);
                    verifiedWriter.newLine();
                    verifiedWriter.flush();
                } catch (IOException e) {
                    Bukkit.getLogger().severe("DDoSDefender: Failed to add verified player " + playerName + ": " + e.getMessage());
                }
            }
        }

        private void addInvalidatedPlayer(String playerName) {
            if (invalidatedPlayerNames.putIfAbsent(playerName, true) == null) {
                try {
                    invalidatedWriter.write(playerName); // Keep original casing
                    invalidatedWriter.newLine();
                    invalidatedWriter.flush();
                } catch (IOException e) {
                    Bukkit.getLogger().severe("DDoSDefender: Failed to add invalidated player " + playerName + ": " + e.getMessage());
                }
            }
            int currentCount = invalidationCounter.incrementAndGet();
            if (currentCount >= 40) {
                Bukkit.getLogger().warning("⚠️ **DDoSDefender: Successfully Mitigated a Bot Attack!** ⚠️");
                invalidationCounter.set(0);
            }
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

        @Getter
        private static class MojangResponse {
            private String id;
            private String name;
            private Boolean legacy;
            private Boolean demo;
        }

        private enum MessageType {
            QUEUE_FULL,
            FREQUENT_REQUEST,
            BOT_DETECTED,
            FAILED_VERIFICATION,
            VERIFICATION_LIMIT_REACHED
        }
    }
}