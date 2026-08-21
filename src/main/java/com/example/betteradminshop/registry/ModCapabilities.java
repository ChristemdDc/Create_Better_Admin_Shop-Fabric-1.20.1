package com.example.betteradminshop.registry;

import com.example.betteradminshop.block.PlayerShopBlock;
import com.example.betteradminshop.block.PlayerShopBlockEntity;
import com.example.betteradminshop.block.ShopPart;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Capabilities de ítems de la tienda de jugador.
 *
 * En el modelo (orientación NORTH) el chute de IMPORT está en X[22,26] — la
 * parte {@link ShopPart#RIGHT} del multibloque — y el de EXPORT en X[6,10] —
 * la parte {@link ShopPart#ORIGIN}. Ambos en la cara inferior. Los chutes,
 * funnels de Create y tolvas consultan la capability del BLOQUE adyacente,
 * así que exponemos:
 *   - posición de la parte RIGHT  → handler de import (solo insertar).
 *   - posición de la parte ORIGIN → handler de export (solo extraer).
 * Los bloques superiores no exponen nada. La regla sigue la PARTE, así que
 * rota junto con la tienda para cualquier facing.
 */
public final class ModCapabilities {

    private ModCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, side) -> {
                    if (!(state.getBlock() instanceof PlayerShopBlock)) return null;
                    ShopPart part = state.getValue(PlayerShopBlock.PART);
                    if (part != ShopPart.ORIGIN && part != ShopPart.RIGHT) return null;

                    BlockPos originPos = part.getOriginPos(pos, state.getValue(PlayerShopBlock.FACING));
                    BlockEntity origin = level.getBlockEntity(originPos);
                    if (!(origin instanceof PlayerShopBlockEntity shop)) return null;

                    IItemHandler handler = (part == ShopPart.RIGHT)
                            ? shop.getImportHandler() : shop.getExportHandler();
                    return handler;
                },
                ModBlocks.PLAYER_SHOP.get());
    }
}
