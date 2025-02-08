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

    /**
     * Invokes all methods on the entity that are annotated with the given annotation.
     * Each such method is required to have no parameters.
     */
    private static <A extends java.lang.annotation.Annotation> void invokeAnnotatedMethods(Object entity, Class<A> annotationClass) {
        for (Method m : entity.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(annotationClass)) {
                if (m.getParameterCount() != 0) {
                    throw new IllegalStateException("Method " + m.getName() + " annotated with @"
                            + annotationClass.getSimpleName() + " must have no parameters.");
                }
                try {
                    m.setAccessible(true);
                    m.invoke(entity);
                } catch (Exception e) {
                    throw new RuntimeException("Error calling @" + annotationClass.getSimpleName() +
                            " on " + entity.getClass().getSimpleName(), e);
                }
            }
        }
    }
}
