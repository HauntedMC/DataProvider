package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Marks a field as a many-to-one relationship.
 * The field type is the target entity. The foreign key column is stored in the owning entity.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ManyToOne {
    /**
     * The column name in the database for storing the foreign key.
     * If empty, defaults to the field name appended with "_id".
     */
    String columnName() default "";
}