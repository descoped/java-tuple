package io.descoped.tuple.test;

import io.descoped.tuple.Tuple;

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's JPMS shape, checked at run time. The tests run on the module path, so this fails if they ever fall
 * back to the class path, or if the module starts to export, open or require more than it should.
 */
class ModuleTest {

    private static final Module LIBRARY = Tuple.class.getModule();

    @Test
    void runsAsANamedModule() {
        assertTrue(LIBRARY.isNamed(), "the tests must run on the module path");
        assertEquals("io.descoped.tuple", LIBRARY.getName());
        assertTrue(ModuleTest.class.getModule().isNamed(), "the tests must be a module of their own");
    }

    @Test
    void exportsOnlyTheApiPackage() {
        ModuleDescriptor descriptor = LIBRARY.getDescriptor();
        Set<String> exports = descriptor.exports().stream().map(Exports::source).collect(Collectors.toSet());
        assertEquals(Set.of("io.descoped.tuple"), exports);
        assertTrue(descriptor.exports().stream().noneMatch(Exports::isQualified), "no qualified exports");
        assertEquals(Set.of("io.descoped.tuple"), descriptor.packages(), "one package, so nothing hidden is exported");
    }

    @Test
    void isNotOpenForReflection() {
        ModuleDescriptor descriptor = LIBRARY.getDescriptor();
        assertFalse(descriptor.isOpen());
        assertTrue(descriptor.opens().isEmpty());
        assertFalse(LIBRARY.isOpen("io.descoped.tuple", ModuleTest.class.getModule()));
    }

    @Test
    void requiresNothingButJavaBase() {
        Set<String> requires = LIBRARY.getDescriptor().requires().stream().map(Requires::name).collect(Collectors.toSet());
        assertEquals(Set.of("java.base"), requires);
    }

    @Test
    void internalsAreNotPublic() {
        for (String internal : List.of("TupleEncoder", "TupleDecoder", "TypeCode", "ByteFormat", "MillisecondCounter")) {
            Class<?> type = Class.forName(LIBRARY, "io.descoped.tuple." + internal);
            assertNotNull(type, internal + " not found");
            assertFalse(Modifier.isPublic(type.getModifiers()), internal + " must stay package-private");
        }
    }
}
