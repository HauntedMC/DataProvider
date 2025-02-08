package nl.hauntedmc.dataprovider.orm.util;

import nl.hauntedmc.dataprovider.orm.annotations.FieldMapping;
import nl.hauntedmc.dataprovider.orm.introspection.EntityIntrospector.EntityMetadata;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public class EntityMapper {

    /**
     * Convert an entity to a Map<String,Object> for storing in DB.
     */
    public static Map<String, Object> entityToMap(Object entity, EntityMetadata meta) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();

            // ID field
            Field idField = meta.getIdField();
            Object idVal = idField.get(entity);

            // if name() not in FieldMapping, default is the field name (lowercased).
            String idName = getFieldName(idField);
            result.put(idName, idVal);

            // other fields
            for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
                Field f = e.getKey();
                // skip if it's the same as idField
                if (f == idField) continue;

                String colName = getFieldName(f);
                Object val = f.get(entity);
                result.put(colName, val);
            }

            return result;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to map entity to row/doc", e);
        }
    }

    /**
     * Convert a DB row/doc (Map<String,Object>) into a Java entity.
     */
    public static <T> T mapRowToEntity(Map<String, Object> data, Class<T> clazz, EntityMetadata meta) {
        try {
            T instance = clazz.getDeclaredConstructor().newInstance();

            // ID
            Field idField = meta.getIdField();
            Object rawId = data.get(getFieldName(idField));
            if (rawId != null) {
                setFieldValue(idField, instance, rawId);
            }

            // other fields
            for (Map.Entry<Field, FieldMapping> e : meta.getMappedFields().entrySet()) {
                Field f = e.getKey();
                if (f == idField) continue;

                Object rawVal = data.get(getFieldName(f));
                if (rawVal != null) {
                    setFieldValue(f, instance, rawVal);
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row/doc to entity " + clazz.getName(), e);
        }
    }

    private static String getFieldName(Field f) {
        FieldMapping fm = f.getAnnotation(FieldMapping.class);
        if (fm != null && !fm.name().isEmpty()) {
            return fm.name();
        }
        // fallback: field name in lower case
        return f.getName().toLowerCase();
    }

    private static void setFieldValue(Field field, Object instance, Object rawValue) throws IllegalAccessException {
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
            // if raw is Number, interpret 0/1
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
}
