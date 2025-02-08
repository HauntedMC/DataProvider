package nl.hauntedmc.dataprovider.orm.lifecycle;

import nl.hauntedmc.dataprovider.orm.annotations.PostLoad;
import nl.hauntedmc.dataprovider.orm.annotations.PreSave;

import java.lang.reflect.Method;

public class EntityLifecycle {

    /**
     * Call any @PreSave methods on the entity (before saving).
     */
    public static void callPreSave(Object entity) {
        for (Method m : entity.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(PreSave.class)) {
                try {
                    m.setAccessible(true);
                    m.invoke(entity);
                } catch (Exception e) {
                    throw new RuntimeException("Error calling @PreSave on " + entity.getClass(), e);
                }
            }
        }
    }

    /**
     * Call any @PostLoad methods on the entity (after loading from DB).
     */
    public static void callPostLoad(Object entity) {
        for (Method m : entity.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(PostLoad.class)) {
                try {
                    m.setAccessible(true);
                    m.invoke(entity);
                } catch (Exception e) {
                    throw new RuntimeException("Error calling @PostLoad on " + entity.getClass(), e);
                }
            }
        }
    }
}
