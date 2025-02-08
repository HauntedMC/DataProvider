package nl.hauntedmc.dataprovider.orm.introspection;

import nl.hauntedmc.dataprovider.orm.annotations.Entity;
import nl.hauntedmc.dataprovider.orm.annotations.FieldMapping;
import nl.hauntedmc.dataprovider.orm.annotations.Id;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class caches introspected metadata and looks at superclass fields.
 */
public class EntityIntrospector {

    private static final Map<Class<?>, EntityMetadata> cache = new ConcurrentHashMap<>();

    public static class EntityMetadata {
        private final Class<?> clazz;
        private final String entityName;
        private Field idField; // The field annotated with @Id
        private final Map<Field, FieldMapping> mappedFields = new LinkedHashMap<>();

        public EntityMetadata(Class<?> clazz, String entityName) {
            this.clazz = clazz;
            this.entityName = entityName;
        }

        public Class<?> getEntityClass() {
            return clazz;
        }

        public String getEntityName() {
            return entityName;
        }

        public Field getIdField() {
            return idField;
        }

        public void setIdField(Field idField) {
            this.idField = idField;
        }

        public Map<Field, FieldMapping> getMappedFields() {
            return mappedFields;
        }
    }

    /**
     * Parses the entity class, extracting the @Entity name, @Id field, and all @FieldMapping fields.
     */
    public static EntityMetadata introspect(Class<?> clazz) {
        return cache.computeIfAbsent(clazz, clz -> {
            Entity entityAnno = clz.getAnnotation(Entity.class);
            if (entityAnno == null) {
                throw new IllegalArgumentException("Class " + clz.getName() + " is not annotated with @Entity");
            }

            String entityName = entityAnno.name().isEmpty()
                    ? clz.getSimpleName().toLowerCase()
                    : entityAnno.name();

            EntityMetadata meta = new EntityMetadata(clz, entityName);

            // Traverse class hierarchy
            Class<?> current = clz;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.isAnnotationPresent(Id.class) && meta.getIdField() == null) {
                        meta.setIdField(f);
                        f.setAccessible(true);
                    }
                    if (f.isAnnotationPresent(FieldMapping.class)) {
                        meta.getMappedFields().put(f, f.getAnnotation(FieldMapping.class));
                        f.setAccessible(true);
                    }
                }
                current = current.getSuperclass();
            }

            if (meta.getIdField() == null) {
                throw new IllegalStateException("No @Id field found in " + clz.getName());
            }
            return meta;
        });
    }
}
