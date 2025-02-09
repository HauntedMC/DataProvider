package nl.hauntedmc.dataprovider.orm.util;

import nl.hauntedmc.dataprovider.orm.EntityManager;
import nl.hauntedmc.dataprovider.orm.annotations.CascadeType;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector.EntityMetadata;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;

public class CascadeHelper {

    /**
     * Handles cascade persist for OneToMany relationships.
     * For each OneToMany field with cascade PERSIST or ALL, it will save the child entities.
     * It also sets the inverse many-to-one field (specified by mappedBy) to the parent entity.
     */
    public static CompletableFuture<Void> cascadePersist(Object parentEntity, EntityMetadata meta, EntityManager em) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var entry : meta.getOneToManyMappings().entrySet()) {
            Field collectionField = entry.getKey();
            EntityIntrospector.OneToManyMapping mapping = entry.getValue();
            // Check if cascade includes PERSIST or ALL
            boolean cascadePersist = false;
            for (CascadeType ct : mapping.getCascade()) {
                if (ct == CascadeType.PERSIST || ct == CascadeType.ALL) {
                    cascadePersist = true;
                    break;
                }
            }
            if (!cascadePersist) {
                continue;
            }
            try {
                Object value = collectionField.get(parentEntity);
                if (value instanceof Collection) {
                    Collection<?> children = (Collection<?>) value;
                    for (Object child : children) {
                        // Set the inverse relationship field on the child entity
                        try {
                            Field childField = child.getClass().getDeclaredField(mapping.getMappedBy());
                            childField.setAccessible(true);
                            childField.set(child, parentEntity);
                        } catch (NoSuchFieldException | IllegalAccessException e) {
                            return CompletableFuture.failedFuture(new RuntimeException("Failed to set inverse relation field: " + mapping.getMappedBy(), e));
                        }
                        // Save the child entity
                        futures.add(em.save(child));
                    }
                }
            } catch (IllegalAccessException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
