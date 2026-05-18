package icyllis.modernui.mc.mixin;

import icyllis.arc3d.core.PixelUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Field;

@Mixin(PixelUtils.class)
public class MixinPixelUtils {

    /**
     * @author Fodoth_jinzi89
     * @reason Compatibility with LWJGL 3.4+ and Java 25
     */
    @SuppressWarnings({"removal", "deprecation"})
    @Overwrite
    private static sun.misc.Unsafe getUnsafe() {
        try {
            Class<?> memoryUtil = Class.forName("org.lwjgl.system.MemoryUtil");
            Field f = memoryUtil.getDeclaredField("UNSAFE");
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof sun.misc.Unsafe unsafe) {
                return unsafe;
            }
        } catch (Throwable ignored) {
        }

        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Throwable e) {
            throw new RuntimeException("Unable to obtain Unsafe on this JVM", e);
        }
    }
}
