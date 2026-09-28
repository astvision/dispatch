package dispatch;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Windows hands a path to native code, such as a library being loaded or java.exe reading its own arguments, in its ANSI
 * code page ({@code native.encoding}), where letters outside it arrive as "?": Cyrillic on English Windows, and Ө and Ү
 * even on Mongolian Windows, whose code page is 1251. Such a path can still be given by its 8.3 short name
 * ({@code C:\Users\5C0E~1}), whose letters every code page has; Windows keeps short names on its system drive.
 */
public final class AnsiPaths {

    private AnsiPaths() {
    }

    /** Windows only: {@code path} as this machine's code page can spell it, itself or its short name; empty if neither. */
    public static Optional<String> of(Path path) {
        return of(path.toString(), Charset.forName(System.getProperty("native.encoding")), AnsiPaths::shortName);
    }

    static Optional<String> of(String path, Charset codePage, UnaryOperator<String> shortName) {
        if (codePage.newEncoder().canEncode(path)) {
            return Optional.of(path);
        }
        String shortPath = shortName.apply(path);
        return codePage.newEncoder().canEncode(shortPath) ? Optional.of(shortPath) : Optional.empty();
    }

    /** GetShortPathNameW: the path itself when its volume keeps no short names. */
    private static String shortName(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle getShortPathName = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("kernel32", arena).find("GetShortPathNameW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            int capacity = 32_767;
            MemorySegment shortPath = arena.allocate(ValueLayout.JAVA_CHAR, capacity);
            int length = (int) getShortPathName.invokeExact(arena.allocateFrom(path, StandardCharsets.UTF_16LE), shortPath,
                    capacity);
            return length > 0 && length < capacity ? shortPath.getString(0, StandardCharsets.UTF_16LE) : path;
        } catch (Throwable e) {
            throw new IllegalStateException("cannot ask Windows for the short name of " + path, e);
        }
    }
}
