package net.minestom.server.network.packet.client.handler;

import net.minestom.server.network.packet.client.play.*;

public class ClientPlayPacketsHandler extends ClientPacketsHandler {

    public ClientPlayPacketsHandler() {
        register(0x00, ClientTeleportConfirmPacket::new);
        register(0x01, ClientTabCompletePacket::new);
        register(0x02, ClientChatMessagePacket::new);
        register(0x03, ClientStatusPacket::new);
        register(0x04, ClientSettingsPacket::new);
        register(0x05, ClientWindowConfirmationPacket::new);
        register(0x06, ClientEnchantItemPacket::new);
        register(0x07, ClientClickWindowPacket::new);
        register(0x08, ClientCloseWindow::new);
        register(0x09, ClientPluginMessagePacket::new);
        register(0x0A, ClientInteractEntityPacket::new);
        register(0x0B, ClientKeepAlivePacket::new);
        register(0x0C, ClientPlayerPositionPacket::new);
        register(0x0D, ClientPlayerPositionAndLookPacket::new);
        register(0x0E, ClientPlayerLookPacket::new);
        register(0x0F, ClientPlayerPacket::new);
        register(0x12, ClientPlayerAbilitiesPacket::new);
        register(0x13, ClientPlayerDiggingPacket::new);
        register(0x14, ClientEntityActionPacket::new);
        register(0x15, ClientSteerVehiclePacket::new);
        register(0x16, ClientResourcePackStatusPacket::new);
        register(0x17, ClientHeldItemChangePacket::new);
        register(0x18, ClientCreativeInventoryActionPacket::new);
        register(0x19, ClientUpdateSignPacket::new);
        register(0x1A, ClientAnimationPacket::new);
        register(0x1B, ClientSpectatePacket::new);
        register(0x1C, ClientPlayerBlockPlacementPacket::new);
    }
}
