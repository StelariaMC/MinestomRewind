package net.minestom.server.network.packet.client.play;

import net.minestom.server.network.packet.client.ClientPlayPacket;
import net.minestom.server.utils.binary.BinaryReader;
import org.jetbrains.annotations.NotNull;

public class ClientInteractEntityPacket extends ClientPlayPacket {

    public int targetId;
    public Type type;
    public int hand;
    public float x;
    public float y;
    public float z;

    @Override
    public void read(@NotNull BinaryReader reader) {
        this.targetId = reader.readVarInt();
        this.type = Type.values()[reader.readVarInt()];

        switch (type) {
            case INTERACT_AT:
                this.x = reader.readFloat();
                this.y = reader.readFloat();
                this.z = reader.readFloat();
                break;
        }

        switch (type) {
            case INTERACT:
            case INTERACT_AT:
                this.hand = reader.readVarInt();
                break;
        }
    }

    public enum Type {
        INTERACT,
        ATTACK,
        INTERACT_AT
    }
}
