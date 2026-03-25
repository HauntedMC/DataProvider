package nl.hauntedmc.dataprovider.internal.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginCallerChainResolverTest {

    @Test
    void validatesArguments() {
        assertThrows(NullPointerException.class, () -> PluginCallerChainResolver.resolveNearestMappedCaller(
                null,
                callerLoader -> "plugin",
                "missing"
        ));
        assertThrows(NullPointerException.class, () -> PluginCallerChainResolver.resolveNearestMappedCaller(
                List.of(),
                null,
                "missing"
        ));
        assertThrows(NullPointerException.class, () -> PluginCallerChainResolver.resolveNearestMappedCaller(
                List.of(),
                callerLoader -> "plugin",
                null
        ));
    }

    @Test
    void returnsNearestMappedPluginCaller() {
        ClassLoader nearestPluginLoader = new ClassLoader() {
        };
        ClassLoader outerPluginLoader = new ClassLoader() {
        };

        CallerContext callerContext = PluginCallerChainResolver.resolveNearestMappedCaller(
                List.of(nearestPluginLoader, outerPluginLoader),
                callerLoader -> {
                    if (callerLoader == nearestPluginLoader) {
                        return "proxyfeatures";
                    }
                    if (callerLoader == outerPluginLoader) {
                        return "shared-wrapper";
                    }
                    return null;
                },
                "missing"
        );

        assertEquals("proxyfeatures", callerContext.pluginId());
        assertSame(nearestPluginLoader, callerContext.classLoader());
    }

    @Test
    void skipsUnmappedLoadersUntilPluginCallerIsFound() {
        ClassLoader libraryLoader = new ClassLoader() {
        };
        ClassLoader pluginLoader = new ClassLoader() {
        };

        CallerContext callerContext = PluginCallerChainResolver.resolveNearestMappedCaller(
                List.of(libraryLoader, pluginLoader),
                callerLoader -> callerLoader == pluginLoader ? "dataregistry" : null,
                "missing"
        );

        assertEquals("dataregistry", callerContext.pluginId());
        assertSame(pluginLoader, callerContext.classLoader());
    }

    @Test
    void throwsWhenNoPluginCallerIsMapped() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                PluginCallerChainResolver.resolveNearestMappedCaller(
                        List.of(new ClassLoader() {
                        }),
                        callerLoader -> null,
                        "missing"
                ));

        assertEquals("missing", exception.getMessage());
    }
}
