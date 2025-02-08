package nl.hauntedmc.dataprovider.orm.util;

import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector.EntityMetadata;
import nl.hauntedmc.dataprovider.orm.annotations.FieldMapping;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public class EntityMapper {

    /**
     * Converts an entity instance into a Map for either relational or doc usage.
     */
    public static Map<String, Object> entityToMap(Object entity, EntityMetadata meta) throws IllegalAccessException {
        Map<String, Object> result = new LinkedHashMap<>();

        // handle ID field
        Field idField = meta.getIdField();
        Object idValue = idField.get(entity);
        String idName = getFieldName(idField);
        result.put(idName, idValue);

        // handle other mapped fields
        for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
            Field f = e.getKey();
            // skip if this is the @Id field too
            if (f == idField) continue;

            String columnOrFieldName = getFieldName(f);
            Object value = f.get(entity);
            result.put(columnOrFieldName, value);
        }

        return result;
    }

    /**
     * Convert a row/doc from the DB into an entity instance.
     */
    public static <T> T mapRowToEntity(Map<String, Object> data, Class<T> clazz, EntityMetadata meta) {
        try {
            T instance = clazz.getDeclaredConstructor().newInstance();

            // ID
            Field idField = meta.getIdField();
            Object idVal = data.get(getFieldName(idField));
            if (idVal != null) {
                setFieldValue(idField, instance, idVal);
            }

            // other fields
            for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
                Field f = e.getKey();
                if (f == idField) continue;

                Object val = data.get(getFieldName(f));
                if (val != null) {
                    setFieldValue(f, instance, val);
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row to entity " + clazz.getName(), e);
        }
    }

    /**
     * For doc-based usage, same logic (we can unify them).
     */
    public static <T> T mapDocumentToEntity(Map<String, Object> doc, Class<T> clazz, EntityMetadata meta) {
        return mapRowToEntity(doc, clazz, meta);
    }

    private static void setFieldValue(Field field, Object instance, Object rawValue) throws IllegalAccessException {
        // Convert the value if needed (like your convertValue logic).
        // For simplicity, we do naive conversions:
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
        } else if ((targetType == boolean.class || targetType == Boolean.class)) {
            // guess logic
            if (rawValue instanceof Boolean) {
                field.set(instance, rawValue);
            } else if (rawValue instanceof Number) {
                field.set(instance, ((Number) rawValue).intValue() != 0);
            } else {
                field.set(instance, Boolean.parseBoolean(rawValue.toString()));
            }
        } else {
            // fallback
            field.set(instance, rawValue);
        }
    }

    private static String getFieldName(Field f) {
        FieldMapping fm = f.getAnnotation(FieldMapping.class);
        if (fm != null && !fm.name().isEmpty()) {
            return fm.name();
        }
        return f.getName().toLowerCase();
    }
}
