package com.rtm516.mcxboxbroadcast.core.nethernet.transfer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.List;

/**
 * Sends every packet as its own batch, as NetherNet already delivers whole messages
 */
@ChannelHandler.Sharable
public class NetherNetPacketEncoder extends MessageToMessageEncoder<BedrockPacketWrapper> {
    public static final String NAME = "nethernet-encoder";
    public static final NetherNetPacketEncoder INSTANCE = new NetherNetPacketEncoder();

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockPacketWrapper packet, List<Object> out) {
        ByteBuf message = packet.getPacketBuffer();
        if (message == null) {
            throw new IllegalArgumentException("BedrockPacket is not encoded");
        }

        CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(2);
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        try {
            ByteBuf header = ctx.alloc().ioBuffer(5);
            VarInts.writeUnsignedInt(header, message.readableBytes());
            buf.addComponent(true, header);
            buf.addComponent(true, message.retain());

            batch.addPacket(packet.retain());
            batch.setUncompressed(buf.retain());
            out.add(batch.retain());
        } finally {
            buf.release();
            batch.release();
        }
    }
}
