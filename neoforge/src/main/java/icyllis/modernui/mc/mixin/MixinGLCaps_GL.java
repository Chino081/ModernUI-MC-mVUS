package icyllis.modernui.mc.mixin;

import icyllis.arc3d.opengl.GLCaps_GL;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GLCaps_GL.class)
public class MixinGLCaps_GL {

    /**
     * @author Fodoth_jinzi89
     * @reason Fix LWJGL 3.4.x compatibility (nglDeleteSync removed)
     */
    @Overwrite
    public void glDeleteSync(long sync) {
        if (sync != 0L) {
            GL32C.glDeleteSync(sync);
        }
    }
}
