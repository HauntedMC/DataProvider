package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Marks a field as a one-to-many relationship.
 * The field type should be a Collection of the target entity.
 * The 'mappedBy' attribute indicates the field in the target entity that owns the relationship.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OneToMany {
    /**
     * The target entity class of the relationship.
     */
    Class<?> targetEntity();

    /**
     * The field in the target entity that maps back to this entity.
     */
    String mappedBy();
}
