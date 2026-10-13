import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.*;

/** Local, fingerprint-guarded repair of CPF 2.2.21-0.3.0 exception root restoration. */
public class NativeShadowStackPatch {
    private static final String CLASS = "org/jetbrains/kotlin/backend/konan/llvm/FunctionGenerationContext.class";
    private static final String ORIGINAL = "4b94c8ea05da4dfecdf53e9cf23e2b93d8752e738c1e6efdf327b990664c91a1";
    private static final String COMPILER = "konan/lib/kotlin-native-compiler-embeddable.jar";
    private static final String REVISION = "shadow-stack-exception-v1";

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] compilerClass(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(CLASS);
            if (entry == null) throw new IOException("Missing guarded compiler class: " + jar);
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static byte[] patch(byte[] original) throws Exception {
        if (!hash(original).equals(ORIGINAL)) {
            throw new IllegalStateException("Unknown CPF compiler fingerprint; refusing to patch. Run full Native regression on the new toolchain.");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        int replacements = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("gxxLandingpad") ||
                !method.desc.startsWith("(ILjava/lang/String;ZZ)")) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("getEnableStackmap") &&
                    call.owner.equals("org/jetbrains/kotlin/backend/konan/llvm/FunctionGenerationContext") &&
                    call.desc.equals("()Z")) {
                    // Keep restoreFrame's own condition, but do not gate ShadowStack restoration
                    // on OHOS-only precise stack maps. The JVM stack effect remains identical.
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(Opcodes.POP));
                    replacement.add(new InsnNode(Opcodes.ICONST_1));
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                    replacements++;
                }
            }
        }
        if (replacements != 1) throw new IllegalStateException("Expected exactly one exception-frame gate, found " + replacements);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void patchJar(Path source, Path target, byte[] repaired) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".patching");
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
            for (ZipEntry entry : Collections.list(input.entries())) {
                if (entry.getName().matches("META-INF/.*\\.(SF|RSA|DSA)")) {
                    throw new IOException("Signed compiler jar cannot be patched");
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                if (!entry.isDirectory()) output.write(entry.getName().equals(CLASS) ? repaired :
                    input.getInputStream(entry).readAllBytes());
                output.closeEntry();
            }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void prepare(Path source, Path destination, byte[] repaired) throws Exception {
        Path marker = destination.resolve("deadliner-shadow-stack-fix.txt");
        Path targetJar = destination.resolve(COMPILER);
        String stamp = REVISION + "\n" + source + "\n" + hash(repaired) + "\n";
        if (Files.exists(marker) && Files.readString(marker).equals(stamp) &&
            Files.exists(targetJar) && hash(compilerClass(targetJar)).equals(hash(repaired))) return;
        if (Files.exists(destination)) throw new IOException("Incomplete/stale toolchain overlay; preserve and move it aside before rebuilding: " + destination);
        Files.createDirectories(destination);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                Path relative = source.relativize(path);
                // Bundled code was generated by the broken compiler; rebuild caches with the fix.
                if (relative.equals(Path.of("klib", "cache"))) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Path relative = source.relativize(path);
                if (relative.equals(Path.of(COMPILER))) return FileVisitResult.CONTINUE;
                Path output = destination.resolve(relative);
                // Immutable SDK files may share storage; the compiler jar and caches never do.
                try { Files.createLink(output, path); }
                catch (IOException | UnsupportedOperationException ignored) { Files.copy(path, output, StandardCopyOption.COPY_ATTRIBUTES); }
                return FileVisitResult.CONTINUE;
            }
        });
        patchJar(source.resolve(COMPILER), targetJar, repaired);
        if (!hash(compilerClass(targetJar)).equals(hash(repaired))) throw new IOException("Repaired compiler verification failed");
        Files.writeString(marker, stamp);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: NativeShadowStackPatch.java <original-native-home> <overlay-home>");
        Path source = Path.of(args[0]).toRealPath();
        Path destination = Path.of(args[1]).toAbsolutePath().normalize();
        if (destination.equals(source) || destination.startsWith(source)) throw new IllegalArgumentException("Do not modify the original toolchain");
        byte[] original = compilerClass(source.resolve(COMPILER));
        Files.createDirectories(destination.getParent());
        Path lockPath = destination.resolveSibling(destination.getFileName() + ".lock");
        // Concurrent Gradle clients must never observe or replace a partial overlay.
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            prepare(source, destination, patch(original));
        }
        System.out.println("CPF Native ShadowStack exception repair verified: " + destination);
    }
}
