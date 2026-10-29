package com.rtm516.mcxboxbroadcast.core.nethernet.transfer;

import com.rtm516.mcxboxbroadcast.core.Constants;
import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.ChatRestrictionLevel;
import org.cloudburstmc.protocol.bedrock.data.EduSharedUriResource;
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.JigsawStructureDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Logs the player into an empty world and transfers them to the target server
 */
public class TransferPacketHandler implements BedrockPacketHandler {
    private final BedrockServerSession session;
    private final String targetHost;
    private final int targetPort;
    private final JoinIdentities.Identity offerIdentity;
    private final StorageManager.PlayerHistoryStorage playerHistory;
    private final Logger logger;

    private ChainValidationResult.IdentityData identityData;
    private boolean networkSettingsRequested = false;
    private boolean transferred = false;

    public TransferPacketHandler(BedrockServerSession session, String targetHost, int targetPort, JoinIdentities.Identity offerIdentity, StorageManager.PlayerHistoryStorage playerHistory, Logger logger) {
        this.session = session;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.offerIdentity = offerIdentity;
        this.playerHistory = playerHistory;
        this.logger = logger;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        BedrockPacketHandler.super.handlePacket(packet);
        return PacketSignal.HANDLED;
    }

    /**
     * Versions we accept, matching what Geyser 2.12.1 supports
     */
    private static final List<BedrockCodec> SUPPORTED_CODECS = List.of(
        Bedrock_v2168_hotfix4.CODEC,
        Bedrock_v2169.CODEC,
        Bedrock_v2193.CODEC,
        Constants.BEDROCK_CODEC
    );

    private static BedrockCodec codecFor(int protocolVersion) {
        for (BedrockCodec codec : SUPPORTED_CODECS) {
            if (codec.getProtocolVersion() == protocolVersion) {
                return codec;
            }
        }
        return null;
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        int clientProtocolVersion = packet.getProtocolVersion();
        BedrockCodec codec = codecFor(clientProtocolVersion);

        if (codec == null) {
            int newest = Constants.BEDROCK_CODEC.getProtocolVersion();
            logger.debug("Refusing NetherNet client on unsupported protocol " + clientProtocolVersion);
            session.disconnect(clientProtocolVersion > newest ? "disconnectionScreen.outdatedServer" : "disconnectionScreen.outdatedClient");
            return PacketSignal.HANDLED;
        }

        session.setCodec(codec);

        NetworkSettingsPacket networkSettingsPacket = new NetworkSettingsPacket();
        networkSettingsPacket.setCompressionThreshold(0);
        networkSettingsPacket.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);

        session.sendPacketImmediately(networkSettingsPacket);
        session.setCompression(PacketCompressionAlgorithm.ZLIB);

        networkSettingsRequested = true;
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        if (!networkSettingsRequested) {
            PlayStatusPacket statusPacket = new PlayStatusPacket();
            statusPacket.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            session.sendPacket(statusPacket);

            session.disconnect();
            return PacketSignal.HANDLED;
        }

        PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        session.sendPacket(status);

        ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
        info.setWorldTemplateId(new UUID(0, 0));
        info.setWorldTemplateVersion("");
        info.setVibrantVisualsForceDisabled(true);
        info.setForcedToAccept(false);
        session.sendPacket(info);

        // Only used for logging and player history, the target server does its own auth
        try {
            ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
            if (!result.signed()) {
                throw new IllegalArgumentException("Chain is not signed");
            }
            PublicKey identityPublicKey = result.identityClaims().parsedIdentityPublicKey();

            byte[] clientDataPayload = EncryptionUtils.verifyClientData(packet.getClientJwt(), identityPublicKey);
            if (clientDataPayload == null) {
                throw new IllegalStateException("Client data isn't signed by the given chain data");
            }

            identityData = result.identityClaims().extraData;
        } catch (AssertionError | Exception error) {
            logger.debug("Failed to validate login packet, using the identity from the offer: " + error);
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheStatusPacket packet) {
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case COMPLETED -> sendStartGameAndTransfer();
            case HAVE_ALL_PACKS -> {
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setExperimentsPreviouslyToggled(false);
                stack.setForcedToAccept(false);
                stack.setGameVersion("*");
                session.sendPacket(stack);
            }
            default -> session.disconnect("disconnectionScreen.resourcePack");
        }
        return PacketSignal.HANDLED;
    }

    @SuppressWarnings("deprecation")
    public void sendStartGameAndTransfer() {
        if (transferred) {
            return;
        }
        transferred = true;

        for (BedrockPacket packet : startGamePackets()) {
            session.sendPacket(packet);
        }

        TransferPacket transferPacket = new TransferPacket();
        transferPacket.setAddress(targetHost);
        transferPacket.setPort(targetPort);
        session.sendPacket(transferPacket);

        recordTransfer();
    }

    /**
     * The minimum the client needs before it accepts a transfer
     */
    @SuppressWarnings("deprecation")
    static List<BedrockPacket> startGamePackets() {
        List<BedrockPacket> packets = new ArrayList<>();

        JigsawStructureDataPacket jigsawStructureDataPacket = new JigsawStructureDataPacket();
        jigsawStructureDataPacket.setJigsawStructureDataTag(NbtMap.fromMap(Map.of(
            "processors", NbtList.EMPTY,
            "template_pools", NbtList.EMPTY,
            "jigsaws", NbtList.EMPTY,
            "structure_sets", NbtList.EMPTY
        )));
        packets.add(jigsawStructureDataPacket);

        VoxelShapesPacket voxelShapesPacket = new VoxelShapesPacket();
        voxelShapesPacket.setNameMap(new HashMap<>());
        voxelShapesPacket.setShapes(new ArrayList<>());
        packets.add(voxelShapesPacket);

        StartGamePacket startGamePacket = new StartGamePacket();
        startGamePacket.setUniqueEntityId(1);
        startGamePacket.setRuntimeEntityId(1);
        startGamePacket.setPlayerGameType(GameType.CREATIVE);
        startGamePacket.setPlayerPosition(Vector3f.from(0, 64 + 2, 0));
        startGamePacket.setRotation(Vector2f.ONE);
        startGamePacket.setPlayerPropertyData(NbtMap.EMPTY);

        startGamePacket.setSeed(0L);
        startGamePacket.setDimensionId(2);
        startGamePacket.setGeneratorId(1);
        startGamePacket.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
        startGamePacket.setCustomBiomeName("");
        startGamePacket.setForceExperimentalGameplay(OptionalBoolean.empty());
        startGamePacket.setLevelGameType(GameType.CREATIVE);
        startGamePacket.setDifficulty(0);
        startGamePacket.setDefaultSpawn(Vector3i.ZERO);
        startGamePacket.setAchievementsDisabled(true);
        startGamePacket.setCurrentTick(-1);
        startGamePacket.setEduEditionOffers(0);
        startGamePacket.setEduFeaturesEnabled(false);
        startGamePacket.setEducationProductionId("");
        startGamePacket.setEduSharedUriResource(EduSharedUriResource.EMPTY);
        startGamePacket.setRainLevel(0);
        startGamePacket.setLightningLevel(0);
        startGamePacket.setMultiplayerGame(true);
        startGamePacket.setBroadcastingToLan(true);
        startGamePacket.getGamerules().add(new GameRuleData<>("showcoordinates", false));
        startGamePacket.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setCommandsEnabled(true);
        startGamePacket.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
        startGamePacket.setTexturePacksRequired(false);
        startGamePacket.setBonusChestEnabled(false);
        startGamePacket.setStartingWithMap(false);
        startGamePacket.setTrustingPlayers(true);
        startGamePacket.setDefaultPlayerPermission(PlayerPermission.VISITOR);
        startGamePacket.setServerChunkTickRange(4);
        startGamePacket.setBehaviorPackLocked(false);
        startGamePacket.setResourcePackLocked(false);
        startGamePacket.setFromLockedWorldTemplate(false);
        startGamePacket.setUsingMsaGamertagsOnly(false);
        startGamePacket.setFromWorldTemplate(false);
        startGamePacket.setWorldTemplateOptionLocked(false);

        startGamePacket.setServerEngine("");
        startGamePacket.setLevelId("");
        startGamePacket.setLevelName("MCXboxBroadcast");
        startGamePacket.setPremiumWorldTemplateId("");
        startGamePacket.setWorldTemplateId(new UUID(0, 0));
        startGamePacket.setCurrentTick(0);
        startGamePacket.setEnchantmentSeed(0);
        startGamePacket.setMultiplayerCorrelationId("");
        startGamePacket.setVanillaVersion("*");

        startGamePacket.setAuthoritativeMovementMode(AuthoritativeMovementMode.SERVER);
        startGamePacket.setRewindHistorySize(0);
        startGamePacket.setServerAuthoritativeBlockBreaking(false);

        startGamePacket.setServerId("");
        startGamePacket.setWorldId("");
        startGamePacket.setScenarioId("");
        startGamePacket.setOwnerId("");

        packets.add(startGamePacket);
        return packets;
    }

    private void recordTransfer() {
        String xuid = null;
        String name = null;
        if (identityData != null) {
            xuid = identityData.xuid;
            name = identityData.displayName;
        } else if (offerIdentity != null) {
            xuid = offerIdentity.xuid();
            name = offerIdentity.gamertag();
        }

        if (xuid == null) {
            logger.info("Transferred an unidentified bedrock client to " + targetHost + ":" + targetPort);
            return;
        }

        logger.info("Transferred bedrock client " + name + " (" + xuid + ") to target server.");
        try {
            if (playerHistory != null) {
                playerHistory.lastSeen(xuid, Instant.now());
            }
        } catch (Exception e) {
            logger.debug("Unable to record last seen for " + xuid + ": " + e.getMessage());
        }
    }
}
