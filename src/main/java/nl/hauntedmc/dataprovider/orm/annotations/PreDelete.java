package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Called right before an entity is deleted.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreDelete {}