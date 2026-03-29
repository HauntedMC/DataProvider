package nl.hauntedmc.dataprovider.internal.identity;

import java.util.List;
import java.util.Objects;

/**
 * Resolves caller class loaders from stack frames.
 */
public final class StackCallerClassLoaderResolver {

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private StackCallerClassLoaderResolver() {
    }

    public static ClassLoader resolveExternalCaller(ClassLoader ownClassLoader) {
        Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");

        return STACK_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(Class::getClassLoader)
                .filter(Objects::nonNull)
                .filter(classLoader -> classLoader != ownClassLoader)
                .findFirst()
                .orElse(null));
    }

    public static List<ClassLoader> resolveExternalCallerChain(ClassLoader ownClassLoader) {
        Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");

        return STACK_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(Class::getClassLoader)
                .filter(Objects::nonNull)
                .filter(classLoader -> classLoader != ownClassLoader)
                .toList());
    }

    public static ClassLoader resolveNearestCallerOutsidePackage(String packagePrefix) {
        Objects.requireNonNull(packagePrefix, "Package prefix cannot be null.");

        return STACK_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(declaringClass -> !declaringClass.getName().startsWith(packagePrefix))
                .map(Class::getClassLoader)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

}
