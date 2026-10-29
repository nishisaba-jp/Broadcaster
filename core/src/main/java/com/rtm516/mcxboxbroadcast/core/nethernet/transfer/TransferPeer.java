package com.rtm516.mcxboxbroadcast.core.nethernet.transfer;

import io.netty.channel.Channel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;

import javax.crypto.SecretKey;

/**
 * A Bedrock peer on a NetherNet channel
 */
public class TransferPeer extends BedrockPeer {
    /**
     * NetherNet acts like RakNet protocol 11
     */
    private static final int EQUIVALENT_RAK_VERSION = 11;

    public TransferPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        super(channel, sessionFactory);
    }

    @Override
    public void enableEncryption(SecretKey secretKey) {
        // NetherNet is already encrypted by DTLS
    }

    @Override
    public int getRakVersion() {
        return EQUIVALENT_RAK_VERSION;
    }
}
