package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Marks a field as the primary ID in either a table or a document.
 *
 * - autoGenerate: in relational, might be AUTO_INCREMENT
 *   in a doc DB, might be auto _id if not set.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Id {
    boolean autoGenerate() default true;
}
