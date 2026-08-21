package com.example.betteradminshop.client;

import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Integración blanda (client-side) con el mod "Cretania Recipes", que crea
 * ítems dinámicos en juego. Esos ítems NO son entradas nuevas del registro:
 * son variantes por componentes de {@code cretania_recipes:custom(_block)}
 * (custom_model_data + custom_name + custom_data{cretania_item}). El registro
 * congelado no los expone, pero el cache client-side de Cretania sí.
 *
 * Se accede por reflexión para no acoplar la build: si Cretania no está, todo
 * queda vacío y el selector funciona igual. Fuente:
 * {@code com.cretania.recipes.client.DynamicItemsClient} — list() / stackFor(slug) / version().
 */
public final class CretaniaCompat {

    /** Ids base de Cretania: se ocultan del selector porque las variantes son lo útil. */
    public static final String BASE_ITEM = "cretania_recipes:custom";
    public static final String BASE_BLOCK = "cretania_recipes:custom_block";

    private static final boolean PRESENT;
    private static Method M_LIST;      // static List<DynItem> list()
    private static Method M_STACK_FOR; // static ItemStack stackFor(String slug)
    private static Method M_VERSION;   // static int version()
    private static Method M_SLUG;      // DynItem.slug()  (resuelto al vuelo)

    // Cache por versión (evita reflejar stackFor() de cientos de ítems en cada apertura)
    private static int cachedVersion = Integer.MIN_VALUE;
    private static List<ItemStack> cached = List.of();

    static {
        boolean ok = false;
        try {
            Class<?> cls = Class.forName("com.cretania.recipes.client.DynamicItemsClient");
            M_LIST = cls.getMethod("list");
            M_STACK_FOR = cls.getMethod("stackFor", String.class);
            M_VERSION = cls.getMethod("version");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        PRESENT = ok;
    }

    private CretaniaCompat() {}

    public static boolean isPresent() {
        return PRESENT;
    }

    private static int version() {
        if (!PRESENT) return 0;
        try {
            return (int) M_VERSION.invoke(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Stacks de todos los ítems dinámicos de Cretania (vacío si el mod no está). */
    public static synchronized List<ItemStack> items() {
        if (!PRESENT) return List.of();
        int v = version();
        if (v == cachedVersion) return cached;
        List<ItemStack> out = new ArrayList<>();
        try {
            List<?> dynItems = (List<?>) M_LIST.invoke(null);
            for (Object dyn : dynItems) {
                if (dyn == null) continue;
                if (M_SLUG == null) M_SLUG = dyn.getClass().getMethod("slug");
                String slug = (String) M_SLUG.invoke(dyn);
                ItemStack st = (ItemStack) M_STACK_FOR.invoke(null, slug);
                if (st != null && !st.isEmpty()) out.add(st.copyWithCount(1));
            }
        } catch (Throwable t) {
            // Cualquier cambio de API en Cretania degrada a "sin ítems", sin romper el selector.
            out = List.of();
        }
        cachedVersion = v;
        cached = out;
        return out;
    }
}
