package nl.hauntedmc.dataprovider.orm.introspection;

import nl.hauntedmc.dataprovider.orm.annotations.Entity;
import nl.hauntedmc.dataprovider.orm.annotations.FieldMapping;
import nl.hauntedmc.dataprovider.orm.annotations.Id;
import nl.hauntedmc.dataprovider.orm.annotations.ManyToOne;
import nl.hauntedmc.dataprovider.orm.annotations.OneToMany;
import nl.hauntedmc.dataprovider.orm.annotations.CascadeType;

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
        private final Map<Field, ManyToOneMapping> manyToOneMappings = new LinkedHashMap<>();
        private final Map<Field, OneToManyMapping> oneToManyMappings = new LinkedHashMap<>();

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

        public Map<Field, ManyToOneMapping> getManyToOneMappings() {
            return manyToOneMappings;
        }

        public Map<Field, OneToManyMapping> getOneToManyMappings() {
            return oneToManyMappings;
        }
    }

    /**
     * Parses the entity class, extracting the @Entity name, @Id field, and all @FieldMapping fields.
     * Also extracts relationship annotations (@ManyToOne and @OneToMany).
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
                    if (f.isAnnotationPresent(ManyToOne.class)) {
                        ManyToOne annotation = f.getAnnotation(ManyToOne.class);
                        String columnName = annotation.columnName();
                        if (columnName.isEmpty()) {
                            columnName = f.getName() + "_id";
                        }
                        meta.getManyToOneMappings().put(f, new ManyToOneMapping(columnName, f.getType()));
                        f.setAccessible(true);
                    }
                    if (f.isAnnotationPresent(OneToMany.class)) {
                        OneToMany annotation = f.getAnnotation(OneToMany.class);
                        Class<?> targetEntity = annotation.targetEntity();
                        String mappedBy = annotation.mappedBy();
                        CascadeType[] cascade = annotation.cascade();
                        meta.getOneToManyMappings().put(f, new OneToManyMapping(mappedBy, targetEntity, cascade));
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

    public static class ManyToOneMapping {
        private final String columnName;
        private final Class<?> targetEntity;

        public ManyToOneMapping(String columnName, Class<?> targetEntity) {
            this.columnName = columnName;
            this.targetEntity = targetEntity;
        }

        public String getColumnName() {
            return columnName;
        }

        public Class<?> getTargetEntity() {
            return targetEntity;
        }
    }

    public static class OneToManyMapping {
        private final String mappedBy;
        private final Class<?> targetEntity;
        private final CascadeType[] cascade;

        public OneToManyMapping(String mappedBy, Class<?> targetEntity, CascadeType[] cascade) {
            this.mappedBy = mappedBy;
            this.targetEntity = targetEntity;
            this.cascade = cascade;
        }

        public String getMappedBy() {
            return mappedBy;
        }

        public Class<?> getTargetEntity() {
            return targetEntity;
        }

        public CascadeType[] getCascade() {
            return cascade;
        }
    }
}
