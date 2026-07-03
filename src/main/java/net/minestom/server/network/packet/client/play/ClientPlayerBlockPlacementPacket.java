package net.minestom.server.network.packet.client.play;

import net.minestom.server.network.packet.client.ClientPlayPacket;
import net.minestom.server.utils.BlockPosition;
import net.minestom.server.utils.binary.BinaryReader;
import org.jetbrains.annotations.NotNull;

public class ClientPlayerBlockPlacementPacket extends ClientPlayPacket {

    public BlockPosition blockPosition;
    public int blockFace;
    public int hand;
    public byte cursorPositionX, cursorPositionY, cursorPositionZ;

    @Override
    public void read(@NotNull BinaryReader reader) {
        this.blockPosition = reader.readBlockPosition();
        this.blockFace = reader.readVarInt();
        this.hand = reader.readVarInt();
        this.cursorPositionX = reader.readByte();
        this.cursorPositionY = reader.readByte();
        this.cursorPositionZ = reader.readByte();
    }

}
