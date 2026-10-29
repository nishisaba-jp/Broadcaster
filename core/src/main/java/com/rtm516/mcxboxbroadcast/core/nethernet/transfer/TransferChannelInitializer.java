package com.rtm516.mcxboxbroadcast.core.nethernet.transfer;

import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

/**
 * Bedrock pipeline for NetherNet connections we accept
 */
public class TransferChannelInitializer extends BedrockServerInitializer {
    private static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    private final String targetHost;
    private final int targetPort;
    private final JoinIdentities identities;
    private final StorageManager.PlayerHistoryStorage playerHistory;
    private final Logger logger;

    public TransferChannelInitializer(String targetHost, int targetPort, JoinIdentities identities, StorageManager.PlayerHistoryStorage playerHistory, Logger logger) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.identities = identities;
        this.playerHistory = playerHistory;
        this.logger = logger;
    }

    @Override
    protected void preInitChannel(Channel channel) {
        // NetherNet framing instead of RakNet's
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);
        channel.pipeline()
            .addLast(NetherNetFrameCodec.NAME, NetherNetFrameCodec.INSTANCE)
            .addLast(CompressionCodec.NAME, new CompressionCodec(NOOP_STRATEGY, false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected void postInitChannel(Channel channel) {
        channel.pipeline().replace(BedrockBatchEncoder.NAME, NetherNetPacketEncoder.NAME, NetherNetPacketEncoder.INSTANCE);
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new TransferPeer(channel, this::createSession);
    }

    @Override
    protected void initSession(BedrockServerSession session) {
        Channel channel = session.getPeer().getChannel();
        JoinIdentities.Identity offerIdentity = identities.remove(channel.attr(NetherNetChildChannel.CONNECTION_ID).get());
        session.setPacketHandler(new TransferPacketHandler(session, targetHost, targetPort, offerIdentity, playerHistory, logger));
    }
}
