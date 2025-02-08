package nl.hauntedmc.dataprovider.orm.lifecycle;

import nl.hauntedmc.dataprovider.orm.annotations.*;

import java.lang.reflect.Method;

public class EntityLifecycle {

    // --- Existing or previously-defined hooks ---
    public static void callPreSave(Object entity) {
        invokeAnnotatedMethods(entity, PreSave.class);
    }

    public static void callPostLoad(Object entity) {
        invokeAnnotatedMethods(entity, PostLoad.class);
    }

    // --- New fine-grained hooks ---
    public static void callPreInsert(Object entity) {
        invokeAnnotatedMethods(entity, PreInsert.class);
    }

    public static void callPostInsert(Object entity) {
        invokeAnnotatedMethods(entity, PostInsert.class);
    }

    public static void callPreUpdate(Object entity) {
        invokeAnnotatedMethods(entity, PreUpdate.class);
    }

    public static void callPostUpdate(Object entity) {
        invokeAnnotatedMethods(entity, PostUpdate.class);
    }

    public static void callPreDelete(Object entity) {
        invokeAnnotatedMethods(entity, PreDelete.class);
    }

    public static void callPostDelete(Object entity) {
        invokeAnnotatedMethods(entity, PostDelete.class);
    }

    // ---------------------------------------------------------------------
    // Helper to reflect and invoke methods on the entity with given annotation
    // ---------------------------------------------------------------------
    private static void invokeAnnotatedMethods(Object entity, Class<?> annotationClass) {
        Method[] methods = entity.getClass().getDeclaredMethods();
        for (Method m : methods) {
            if (m.isAnnotationPresent((Class) annotationClass)) {
                try {
                    m.setAccessible(true);
                    m.invoke(entity);
                } catch (Exception e) {
                    throw new RuntimeException("Error calling @" + annotationClass.getSimpleName()
                            + " on " + entity.getClass().getSimpleName(), e);
                }
            }
        }
    }
}
