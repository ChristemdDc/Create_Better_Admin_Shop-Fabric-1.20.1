package com.example.betteradminshop.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuración común del mod (archivo {@code config/betteradminshop-common.toml}).
 *
 * Sección [mongodb]: integración OPCIONAL con MongoDB. SQLite sigue siendo la
 * base local autoritativa; cuando Mongo está habilitado, el mod ESPEJA cada
 * transacción y publica el estado de cada tienda (ítems, precios, stock) para
 * gestión externa y, a futuro, precios dinámicos con IA.
 */
public final class BetterAdminShopConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue MONGO_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MONGO_URI;
    public static final ModConfigSpec.ConfigValue<String> MONGO_DATABASE;
    public static final ModConfigSpec.ConfigValue<String> MONGO_TX_COLLECTION;
    public static final ModConfigSpec.ConfigValue<String> MONGO_SHOP_COLLECTION;
    public static final ModConfigSpec.BooleanValue MONGO_PUBLISH_STATE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Integración con MongoDB (opcional). SQLite sigue siendo la base local.")
                .push("mongodb");

        MONGO_ENABLED = b
                .comment("Habilitar el espejo a MongoDB. Si es false, todo funciona solo con SQLite.")
                .define("enabled", false);

        MONGO_URI = b
                .comment("URI de conexión. Ej: mongodb://usuario:clave@host:27017 o mongodb+srv://...")
                .define("uri", "mongodb://localhost:27017");

        MONGO_DATABASE = b
                .comment("Nombre de la base de datos.")
                .define("database", "betteradminshop");

        MONGO_TX_COLLECTION = b
                .comment("Colección donde se espejan las transacciones (ventas/compras).")
                .define("transactionsCollection", "transactions");

        MONGO_SHOP_COLLECTION = b
                .comment("Colección donde se publica el estado de cada tienda (ítems/precios/stock).")
                .define("shopsCollection", "shops");

        MONGO_PUBLISH_STATE = b
                .comment("Publicar el estado de las tiendas al cambiar (necesario para gestionar precios/stock desde fuera).")
                .define("publishShopState", true);

        b.pop();

        SPEC = b.build();
    }

    private BetterAdminShopConfig() {}
}
