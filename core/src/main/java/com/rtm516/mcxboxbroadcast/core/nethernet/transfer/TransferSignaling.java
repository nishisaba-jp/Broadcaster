package com.rtm516.mcxboxbroadcast.core.nethernet.transfer;

import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.jose4j.jwt.JwtClaims;
import tel.schich.libdatachannel.LibDataChannelArchDetect;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Accepts joins from the friends list ourselves and transfers the player to the target server,
 * so the server sees the player's own address rather than ours
 */
public class TransferSignaling extends NetherNetXboxRpcSignaling {
    private static volatile boolean nativesLoaded = false;

    private final InetSocketAddress target;
    private final StorageManager.PlayerHistoryStorage playerHistory;
    private final Logger logger;

    private final JoinIdentities identities = new JoinIdentities();
    private final Object lock = new Object();

    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    /**
     * @param localNetworkId The local Network ID to use
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***')
     * @param target The server to transfer players to
     * @param playerHistory The player history to record joins in
     * @param logger The logger to use
     */
    public TransferSignaling(long localNetworkId, String xboxToken, InetSocketAddress target, StorageManager.PlayerHistoryStorage playerHistory, Logger logger) {
        super(localNetworkId, xboxToken);
        this.target = target;
        this.playerHistory = playerHistory;
        this.logger = logger;
    }

    /**
     * Load the libdatachannel natives
     *
     * @param logger The logger to report a failure to
     * @return If the natives are available on this platform
     */
    public static boolean loadNatives(Logger logger) {
        if (nativesLoaded) {
            return true;
        }

        try {
            LibDataChannelArchDetect.initialize();
            nativesLoaded = true;
        } catch (Throwable e) {
            logger.error("Failed to load the libdatachannel native library, falling back to relaying joins", e);
        }
        return nativesLoaded;
    }

    @Override
    public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        // Binding waits on the signaling with no timeout, so only start once it is up
        return super.connect(remoteAddress).thenCompose(iceServers -> startServer().thenApply(ignored -> iceServers));
    }

    private CompletableFuture<Void> startServer() {
        CompletableFuture<Void> started = new CompletableFuture<>();

        synchronized (lock) {
            if (serverChannel != null) {
                started.complete(null);
                return started;
            }

            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(NetherNetChannelFactory.server(this))
                .childHandler(new TransferChannelInitializer(target.getHostString(), target.getPort(), identities, playerHistory, logger))
                .bind(new InetSocketAddress(0))
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        logger.error("Failed to start the transfer server", future.cause());
                        stopServer();
                        started.completeExceptionally(future.cause());
                        return;
                    }

                    synchronized (lock) {
                        serverChannel = future.channel();
                    }
                    logger.info("Transferring joins to " + target.getHostString() + ":" + target.getPort());
                    started.complete(null);
                });
        }

        return started;
    }

    private void stopServer() {
        synchronized (lock) {
            if (serverChannel != null) {
                serverChannel.close();
                serverChannel = null;
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
                bossGroup = null;
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
                workerGroup = null;
            }
            identities.clear();
        }
    }

    @Override
    protected void dispatchSignalToPipeline(String sender, String rawMsg) {
        NetherNetConstants.Signal signal = NetherNetConstants.parseSignal(rawMsg);
        if (signal != null && NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(signal.type())) {
            rememberIdentity(signal.connectionId(), signal.payload());
        }

        super.dispatchSignalToPipeline(sender, rawMsg);
    }

    /**
     * Keep who sent an offer, in case their login packet can't be validated
     */
    private void rememberIdentity(String connectionId, String sdp) {
        try {
            JwtClaims claims = IdentityUtils.validateSdp(sdp);
            identities.put(connectionId, claims.getStringClaimValue("xid"), claims.getStringClaimValue("xname"));
        } catch (Exception e) {
            logger.debug("Unable to read the identity in the offer for " + connectionId);
        }
    }

    @Override
    public void close() {
        stopServer();
        super.close();
    }
}
