package com.example.betteradminshop.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

/**
 * Keybinds del cliente. La tecla para ocultar los recuadros de selección de la
 * tienda viene SIN ASIGNAR por defecto (no molesta a nadie): el jugador debe
 * asignarla en Opciones → Controles para usarla.
 */
public final class ShopKeybinds {

    public static final String CATEGORY = "key.categories.betteradminshop";

    /** Alterna la visibilidad de los recuadros de selección (los ítems siguen visibles). */
    public static final KeyMapping TOGGLE_BOXES = new KeyMapping(
            "key.betteradminshop.toggle_boxes",
            InputConstants.UNKNOWN.getValue(), // -1 = sin asignar
            CATEGORY);

    private static boolean boxesHidden = false;

    public static boolean areBoxesHidden() {
        return boxesHidden;
    }

    public static void toggleBoxes() {
        boxesHidden = !boxesHidden;
    }

    private ShopKeybinds() {}
}
