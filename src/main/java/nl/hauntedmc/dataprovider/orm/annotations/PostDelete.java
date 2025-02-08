package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Called right after an entity is deleted.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostDelete {}