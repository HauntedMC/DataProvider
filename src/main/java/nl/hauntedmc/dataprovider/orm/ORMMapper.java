package nl.hauntedmc.dataprovider.orm;

import nl.hauntedmc.dataprovider.orm.annotations.Column;

import java.lang.reflect.Field;
import java.util.Map;

public class ORMMapper {

    /**
     * Maps a database result (Map<String, Object>) to a Java object of type T.
     *
     * @param result The database row as a Map<String, Object>.
     * @param clazz  The class type to map to.
     * @param <T>    The type parameter.
     * @return The mapped object.
     */
    public static <T> T map(Map<String, Object> result, Class<T> clazz) {
        if (result == null) return null;

        try {
            T instance = clazz.getDeclaredConstructor().newInstance();

            for (Field field : clazz.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column != null && result.containsKey(column.name())) {
                    field.setAccessible(true);
                    Object value = result.get(column.name());

                    // Convert database type to Java type
                    Object convertedValue = convertValue(value, field.getType());

                    field.set(instance, convertedValue);
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Error mapping database result to object: " + clazz.getSimpleName(), e);
        }
    }

    /**
     * Converts database values to appropriate Java types.
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;

        if (targetType == String.class) return value.toString();
        if (targetType == Integer.class || targetType == int.class) return ((Number) value).intValue();
        if (targetType == Long.class || targetType == long.class) return ((Number) value).longValue();
        if (targetType == Double.class || targetType == double.class) return ((Number) value).doubleValue();
        if (targetType == Boolean.class || targetType == boolean.class) return value instanceof Boolean ? value : (Integer) value != 0;

        return value; // Fallback for unsupported types
    }
}
