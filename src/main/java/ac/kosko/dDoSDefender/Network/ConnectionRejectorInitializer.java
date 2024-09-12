package ac.kosko.dDoSDefender.Network;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Log4J2LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

public class ConnectionRejectorInitializer extends ChannelInitializer<Channel> {

    private final JavaPlugin plugin;

    public ConnectionRejectorInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addFirst("connectionRejector", new ConnectionRejector(plugin));
        Bukkit.getLogger().info("ConnectionRejector has been added to the pipeline.");
    }
}

class ConnectionRejector extends ChannelInboundHandlerAdapter {

    private static final int MAX_QUEUE_SIZE = 9;
    private static final int PROCESS_LIMIT = 3;

    private final JavaPlugin plugin;
    private final ConcurrentLinkedQueue<PacketEvent> playerQueue = new ConcurrentLinkedQueue<>();
    private final Semaphore queueLock = new Semaphore(1);

    public ConnectionRejector(JavaPlugin plugin) {
        this.plugin = plugin;

        // debug logging for Netty
        InternalLoggerFactory.setDefaultFactory(Log4J2LoggerFactory.INSTANCE);

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Bukkit.getLogger().info("Received LoginStart packet: " + event.getPacketType());
                handlePlayerConnection(event);
            }
        });

        // Schedule task to process player connection requests every second
        Bukkit.getScheduler().runTaskTimer(plugin, this::processQueue, 20L, 20L);
        Bukkit.getLogger().info("ConnectionRejector scheduled to process queue every second.");
    }

    private boolean isPlayerConnection(PacketEvent event) {
        Player player = event.getPlayer();
        Bukkit.getLogger().info("Checking if packet is from player: " + player);
        return player != null;
    }

    private void handlePlayerConnection(PacketEvent event) {
        Bukkit.getLogger().info("Received LoginStart packet from: " + event.getPlayer().getName());
        queueLock.acquireUninterruptibly();
        try {
            if (playerQueue.size() < MAX_QUEUE_SIZE) {
                if (event.getPacket() != null) {
                    playerQueue.add(event);
                    event.setCancelled(true); // Hold the connection until processed
                    Bukkit.getLogger().info("Added packet to queue. Queue size: " + playerQueue.size());
                } else {
                    Bukkit.getLogger().warning("Invalid packet data. Packet from " + event.getPlayer().getName() + " was rejected.");
                    event.setCancelled(true);
                }
            } else {
                Bukkit.getLogger().warning("Connection queue is full. Packet from " + event.getPlayer().getName() + " was rejected.");
                event.setCancelled(true);
            }
        } finally {
            queueLock.release();
        }
    }

    private void processQueue() {
        Bukkit.getLogger().info("Processing connection queue. Queue size: " + playerQueue.size());
        queueLock.acquireUninterruptibly();
        try {
            int processedCount = 0;
            while (processedCount < PROCESS_LIMIT && !playerQueue.isEmpty()) {
                PacketEvent packetEvent = playerQueue.poll();
                if (packetEvent != null) {
                    packetEvent.setCancelled(false); // Uncancel the event to allow login to continue
                    processedCount++;
                }
            }
        } finally {
            queueLock.release();
        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        Bukkit.getLogger().info("Channel registered: " + ctx.channel().toString());
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        Bukkit.getLogger().info("Channel unregistered: " + ctx.channel().toString());
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Bukkit.getLogger().info("Channel active: " + ctx.channel().toString());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Bukkit.getLogger().info("Channel inactive: " + ctx.channel().toString());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Bukkit.getLogger().severe("Exception caught: " + cause.getMessage());
        super.exceptionCaught(ctx, cause);
    }
}