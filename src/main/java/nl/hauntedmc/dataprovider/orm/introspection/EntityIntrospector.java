package nl.hauntedmc.dataprovider.orm.introspection;

import nl.hauntedmc.dataprovider.orm.annotations.Entity;
import nl.hauntedmc.dataprovider.orm.annotations.FieldMapping;
import nl.hauntedmc.dataprovider.orm.annotations.Id;

import java.lang.reflect.Field;
import java.util.*;

public class EntityIntrospector {

    public static class EntityMetadata {
        private final Class<?> clazz;
        private final String entityName;
        private Field idField;
        private final Map<Field, FieldMapping> mappedFields = new LinkedHashMap<>();

        public EntityMetadata(Class<?> clazz, String entityName) {
            this.clazz = clazz;
            this.entityName = entityName;
        }

        public Class<?> getEntityClass() { return clazz; }
        public String getEntityName() { return entityName; }
        public Field getIdField() { return idField; }
        public Map<Field, FieldMapping> getMappedFields() { return mappedFields; }
    }

    public static EntityMetadata introspect(Class<?> clazz) {
        Entity entityAnno = clazz.getAnnotation(Entity.class);
        if (entityAnno == null) {
            throw new IllegalArgumentException("Class " + clazz.getName() + " is not annotated with @Entity");
        }
        String entityName = entityAnno.name().isEmpty()
                ? clazz.getSimpleName().toLowerCase()
                : entityAnno.name();

        EntityMetadata meta = new EntityMetadata(clazz, entityName);

        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(Id.class)) {
                meta.idField = f;
                f.setAccessible(true);
            }
            FieldMapping fm = f.getAnnotation(FieldMapping.class);
            if (fm != null) {
                meta.mappedFields.put(f, fm);
                f.setAccessible(true);
            }
        }

        if (meta.idField == null) {
            throw new IllegalStateException("No @Id field found in " + clazz.getName());
        }

        return meta;
    }
}
