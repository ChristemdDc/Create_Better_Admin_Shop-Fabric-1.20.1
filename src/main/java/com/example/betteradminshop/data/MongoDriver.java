package com.example.betteradminshop.data;

/**
 * Detecta si el driver de MongoDB está presente en el classpath SIN referenciar
 * ninguna clase de {@code com.mongodb} — por eso esta clase carga siempre,
 * aunque el driver no exista.
 *
 * El resto del código consulta {@link #AVAILABLE} antes de tocar
 * {@link MongoStore} (cuyo bytecode sí referencia {@code com.mongodb}), de modo
 * que MongoStore solo se carga cuando el driver está disponible. Así el mod
 * arranca sin problemas aunque ningún mod aporte el driver de Mongo.
 */
public final class MongoDriver {

    public static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            // initialize=false: comprueba presencia sin inicializar la clase.
            Class.forName("com.mongodb.client.MongoClients", false, MongoDriver.class.getClassLoader());
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        AVAILABLE = available;
    }

    private MongoDriver() {}
}
