package com.example.betteradminshop.client;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Eventos de cliente (bus GAME). Procesa la tecla para ocultar/mostrar los
 * recuadros de selección de la tienda.
 */
@EventBusSubscriber(modid = BetterAdminShop.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ShopClientEvents {

    private ShopClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // consumeClick() devuelve true una vez por pulsación (vacía la cola).
        while (ShopKeybinds.TOGGLE_BOXES.consumeClick()) {
            ShopKeybinds.toggleBoxes();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        ShopKeybinds.areBoxesHidden()
                                ? "§eRecuadros de tienda: §coculto"
                                : "§eRecuadros de tienda: §avisible"), true);
            }
        }
    }
}
