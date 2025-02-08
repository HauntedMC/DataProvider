package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Marks a field as a column/field in the underlying store.
 * For relational, e.g. "varchar(255)" or "int not null".
 * For documents, it's just the doc field name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FieldMapping {

    /**
     * The column or doc field name.
     * If empty, defaults to the Java field name in lowerCase.
     */
    String name() default "";

    /**
     * For relational usage: e.g. "VARCHAR(255) NOT NULL".
     * Document-based impl can ignore it.
     */
    String columnDefinition() default "";

    /**
     * For relational usage: sets NOT NULL
     */
    boolean notNull() default false;
}
