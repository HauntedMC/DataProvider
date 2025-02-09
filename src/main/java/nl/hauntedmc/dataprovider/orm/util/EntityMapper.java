package nl.hauntedmc.dataprovider.orm.util;

import nl.hauntedmc.dataprovider.orm.annotations.FieldMapping;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector;
import nl.hauntedmc.dataprovider.orm.relational.RelationalEntityManager;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;

public class EntityMapper {

    /**
     * Convert an entity to a Map<String,Object> for storing in DB.
     */
    public static Map<String, Object> entityToMap(Object entity, EntityIntrospector.EntityMetadata meta) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();

            // ID field
            Field idField = meta.getIdField();
            Object idVal = idField.get(entity);

            // Use getDatabaseFieldName to ensure proper mapping (including custom names)
            String idName = getDatabaseFieldName(idField);
            result.put(idName, idVal);

            // Other fields annotated with @FieldMapping
            for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
                Field f = e.getKey();
                if (f == idField) continue;
                String colName = getDatabaseFieldName(f);
                Object val = f.get(entity);
                result.put(colName, val);
            }

            // Process ManyToOne relationships: store the foreign key
            for (Map.Entry<Field, EntityIntrospector.ManyToOneMapping> entry : meta.getManyToOneMappings().entrySet()) {
                Field field = entry.getKey();
                Object relatedEntity = field.get(entity);
                String columnName = entry.getValue().getColumnName();
                if (relatedEntity != null) {
                    var relatedMeta = EntityIntrospector.introspect(relatedEntity.getClass());
                    Field relatedIdField = relatedMeta.getIdField();
                    Object relatedId = relatedIdField.get(relatedEntity);
                    result.put(columnName, relatedId);
                } else {
                    result.put(columnName, null);
                }
            }
            // Note: OneToMany is inverse; not stored in this entity's table.
            return result;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to map entity to row/doc", e);
        }
    }

    /**
     * Synchronous version used by DocumentEntityManager.
     */
    public static <T> T mapRowToEntity(Map<String, Object> data, Class<T> clazz, EntityIntrospector.EntityMetadata meta) {
        try {
            T instance = clazz.getDeclaredConstructor().newInstance();

            // Map the ID field
            Field idField = meta.getIdField();
            Object rawId = data.get(getDatabaseFieldName(idField));
            if (rawId != null) {
                setFieldValue(idField, instance, rawId);
            }

            // Map other fields annotated with @FieldMapping
            for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
                Field f = e.getKey();
                if (f == idField) continue;
                Object rawVal = data.get(getDatabaseFieldName(f));
                if (rawVal != null) {
                    setFieldValue(f, instance, rawVal);
                }
            }
            // Note: Relationships are not auto–loaded in document mapping.
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row/doc to entity " + clazz.getName(), e);
        }
    }

    /**
     * Asynchronously maps a DB row (map) into an entity and loads its relationships.
     * This method is used by the RelationalEntityManager.
     */
    public static <T> CompletableFuture<T> mapRowToEntity(Map<String, Object> data, Class<T> clazz,
                                                          EntityIntrospector.EntityMetadata meta,
                                                          RelationalEntityManager em) {
        T instance;
        try {
            instance = clazz.getDeclaredConstructor().newInstance();
            // Map the ID field
            Field idField = meta.getIdField();
            Object rawId = data.get(getDatabaseFieldName(idField));
            if (rawId != null) {
                setFieldValue(idField, instance, rawId);
            }
            // Map fields annotated with @FieldMapping
            for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
                Field f = e.getKey();
                if (f == idField) continue;
                Object rawVal = data.get(getDatabaseFieldName(f));
                if (rawVal != null) {
                    setFieldValue(f, instance, rawVal);
                }
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException("Failed to instantiate entity " + clazz.getName(), e));
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Process ManyToOne relationships: load related entity by foreign key.
        for (Map.Entry<Field, EntityIntrospector.ManyToOneMapping> entry : meta.getManyToOneMappings().entrySet()) {
            Field field = entry.getKey();
            String columnName = entry.getValue().getColumnName();
            Object foreignKey = data.get(columnName);
            if (foreignKey != null) {
                CompletableFuture<Void> future = em.findById(entry.getValue().getTargetEntity(), foreignKey)
                        .thenAccept(relatedEntity -> {
                            try {
                                field.set(instance, relatedEntity);
                            } catch (IllegalAccessException ex) {
                                throw new RuntimeException("Failed to set ManyToOne field " + field.getName(), ex);
                            }
                        });
                futures.add(future);
            }
        }

        // Process OneToMany relationships: load collection from child table.
        for (Map.Entry<Field, EntityIntrospector.OneToManyMapping> entry : meta.getOneToManyMappings().entrySet()) {
            Field field = entry.getKey();
            EntityIntrospector.OneToManyMapping mapping = entry.getValue();
            // Parent's ID is needed
            Object parentId;
            try {
                parentId = meta.getIdField().get(instance);
            } catch (IllegalAccessException e) {
                return CompletableFuture.failedFuture(new RuntimeException("Failed to access id field", e));
            }
            // Determine the child entity's ManyToOne field (using mappedBy)
            var childMeta = EntityIntrospector.introspect(mapping.getTargetEntity());
            Field childField;
            try {
                childField = mapping.getTargetEntity().getDeclaredField(mapping.getMappedBy());
            } catch (NoSuchFieldException e) {
                return CompletableFuture.failedFuture(new RuntimeException("MappedBy field " + mapping.getMappedBy()
                        + " not found in " + mapping.getTargetEntity().getName(), e));
            }
            if (!childField.isAnnotationPresent(nl.hauntedmc.dataprovider.orm.annotations.ManyToOne.class)) {
                return CompletableFuture.failedFuture(new RuntimeException("Field " + mapping.getMappedBy()
                        + " in " + mapping.getTargetEntity().getName() + " is not annotated with @ManyToOne"));
            }
            nl.hauntedmc.dataprovider.orm.annotations.ManyToOne manyToOneAnno = childField.getAnnotation(nl.hauntedmc.dataprovider.orm.annotations.ManyToOne.class);
            String childForeignKeyColumn = manyToOneAnno.columnName();
            if (childForeignKeyColumn.isEmpty()) {
                childForeignKeyColumn = childField.getName() + "_id";
            }

            CompletableFuture<Void> future = em.findByColumn(mapping.getTargetEntity(), childForeignKeyColumn, parentId)
                    .thenAccept(list -> {
                        try {
                            field.set(instance, list);
                        } catch (IllegalAccessException ex) {
                            throw new RuntimeException("Failed to set OneToMany field " + field.getName(), ex);
                        }
                    });
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> instance);
    }

    /**
     * Returns the database field name for the given field.
     * If the FieldMapping annotation specifies a name, use it; otherwise, fallback to the field name in lowerCase.
     */
    public static String getDatabaseFieldName(Field f) {
        FieldMapping fm = f.getAnnotation(FieldMapping.class);
        if (fm != null && !fm.name().isEmpty()) {
            return fm.name();
        }
        return f.getName().toLowerCase();
    }

    /**
     * Sets the field value, with improved support for common types including enums.
     */
    public static void setFieldValue(Field field, Object instance, Object rawValue) throws IllegalAccessException {
        field.setAccessible(true);
        if (rawValue == null) {
            field.set(instance, null);
            return;
        }
        Class<?> targetType = field.getType();

        if (targetType == String.class) {
            field.set(instance, rawValue.toString());
        } else if ((targetType == int.class || targetType == Integer.class) && rawValue instanceof Number) {
            field.set(instance, ((Number) rawValue).intValue());
        } else if ((targetType == long.class || targetType == Long.class) && rawValue instanceof Number) {
            field.set(instance, ((Number) rawValue).longValue());
        } else if ((targetType == double.class || targetType == Double.class) && rawValue instanceof Number) {
            field.set(instance, ((Number) rawValue).doubleValue());
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            if (rawValue instanceof Boolean) {
                field.set(instance, rawValue);
            } else if (rawValue instanceof Number) {
                field.set(instance, ((Number) rawValue).intValue() != 0);
            } else {
                field.set(instance, Boolean.parseBoolean(rawValue.toString()));
            }
        } else if (targetType.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<Enum> enumType = (Class<Enum>) targetType;
            field.set(instance, Enum.valueOf(enumType, rawValue.toString()));
        } else {
            field.set(instance, rawValue);
        }
    }
}
