package com.example.betteradminshop.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Paquete de entrega PROPIO de las tiendas, con el aspecto de la cardboard de
 * Create pero SIN su límite de contenido.
 *
 * Motivo: las cajas de Create guardan su contenido en un handler de 9 slots
 * ({@code PackageItem.SLOTS}), así que una compra de más de 9 stacks perdía lo
 * que no cabía. Aquí el contenido se guarda como una lista NBT propia, sin
 * tope de slots (cada stack sí se divide en counts válidos al empaquetar).
 *
 * No es un {@code PackageItem} de Create a propósito: los packagers, frogports
 * y demás logística de Create lo ignoran, tal como se quiere. Tampoco tiene
 * receta ni aparece en pestañas creativas: solo lo entregan las tiendas.
 */
public class ShopPackageItem extends Item {

    /** Clave del NBT propio donde viaja el contenido. */
    private static final String CONTENTS_KEY = "ShopPackageContents";

    public ShopPackageItem(Properties properties) {
        super(properties);
    }

    // ── Construcción ─────────────────────────────────────────────────────────

    /**
     * Crea un paquete con TODO el contenido indicado (sin límite de stacks).
     * Los counts se dividen a tamaños válidos para el codec de ItemStack.
     */
    public static ItemStack containing(List<ItemStack> items, HolderLookup.Provider registries) {
        ItemStack box = new ItemStack(
                com.example.betteradminshop.registry.ModItems.SHOP_PACKAGE.get());
        ListTag list = new ListTag();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            int remaining = stack.getCount();
            int max = Math.max(1, Math.min(stack.getMaxStackSize(), 99));
            while (remaining > 0) {
                int n = Math.min(remaining, max);
                list.add(stack.copyWithCount(n).save(registries));
                remaining -= n;
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.put(CONTENTS_KEY, list);
        box.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return box;
    }

    /** Contenido del paquete (lista vacía si no lleva nada). */
    public static List<ItemStack> getContents(ItemStack box, HolderLookup.Provider registries) {
        List<ItemStack> out = new ArrayList<>();
        CustomData data = box.get(DataComponents.CUSTOM_DATA);
        if (data == null) return out;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(CONTENTS_KEY)) return out;
        ListTag list = tag.getList(CONTENTS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                ItemStack stack = ItemStack.parseOptional(registries, list.getCompound(i));
                if (!stack.isEmpty()) out.add(stack);
            } catch (Exception ignored) {
                // un stack corrupto no debe impedir abrir el resto del paquete
            }
        }
        return out;
    }

    /** Total de unidades que lleva dentro (para el tooltip). */
    public static int countItems(ItemStack box, HolderLookup.Provider registries) {
        int total = 0;
        for (ItemStack s : getContents(box, registries)) total += s.getCount();
        return total;
    }

    // ── Apertura ─────────────────────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack box = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(box);
        }

        List<ItemStack> contents = getContents(box, level.registryAccess());
        // Consumir una caja de la pila
        box.shrink(1);

        // Lo que no quepa en el inventario cae al suelo.
        for (ItemStack stack : contents) {
            if (stack.isEmpty()) continue;
            ItemStack give = stack.copy();
            // add() devuelve true aunque solo quepa PARTE y deja el sobrante en
            // 'give': hay que mirar el STACK, no el booleano, o se perdería.
            player.getInventory().add(give);
            if (!give.isEmpty()) {
                ItemEntity entity = new ItemEntity(level, player.getX(),
                        player.getY() + 0.5, player.getZ(), give.copy());
                entity.setPickUpDelay(10);
                level.addFreshEntity(entity);
            }
        }

        level.playSound(null, player.blockPosition(), SoundEvents.WOOL_BREAK,
                SoundSource.PLAYERS, 0.8f, 1.2f);

        return InteractionResultHolder.success(box);
    }

    // ── Presentación / restricciones ─────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        HolderLookup.Provider registries = context.registries();
        if (registries == null) return;
        List<ItemStack> contents = getContents(stack, registries);
        if (contents.isEmpty()) {
            tooltip.add(Component.literal("§8Vacío"));
            return;
        }
        int total = 0;
        for (ItemStack s : contents) total += s.getCount();
        tooltip.add(Component.literal("§7Contiene §f" + total + " §7ítems"));
        int shown = Math.min(contents.size(), 5);
        for (int i = 0; i < shown; i++) {
            ItemStack s = contents.get(i);
            tooltip.add(Component.literal("§8· §7" + s.getCount() + "× "
                    + s.getHoverName().getString()));
        }
        if (contents.size() > shown) {
            tooltip.add(Component.literal("§8· … y " + (contents.size() - shown) + " más"));
        }
        tooltip.add(Component.literal("§eClic derecho para abrir"));
    }

    /** Como las cajas de Create: no se puede meter en shulkers ni bundles. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    // ── Entidad propia al soltarlo (mismo comportamiento que Create) ─────────

    /** Al tirarlo con Q no cae como ítem suelto, sino como paquete físico. */
    @Override
    public boolean hasCustomEntity(ItemStack stack) {
        return true;
    }

    @Override
    public net.minecraft.world.entity.Entity createEntity(Level level,
                                                          net.minecraft.world.entity.Entity original,
                                                          ItemStack stack) {
        return ShopPackageEntity.fromDroppedItem(level, original, stack);
    }
}
