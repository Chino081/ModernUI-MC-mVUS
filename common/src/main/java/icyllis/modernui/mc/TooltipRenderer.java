/*
 * Modern UI.
 * Copyright (C) 2019-2023 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

package icyllis.modernui.mc;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import icyllis.arc3d.core.MathUtil;
import icyllis.modernui.graphics.Color;
import icyllis.modernui.mc.mixin.AccessClientTextTooltip;
import icyllis.modernui.mc.text.CharacterStyle;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.inventory.tooltip.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.*;
import net.minecraft.world.item.*;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * An extension that replaces vanilla tooltip style.
 */
@ApiStatus.Internal
public final class TooltipRenderer implements ScrollController.IListener {

    // config value
    public static volatile boolean sTooltip = true;

    public static final int[] sFillColor = new int[4];
    public static final int[] sStrokeColor = new int[4];
    public static volatile float sBorderWidth = 4 / 3f;
    public static volatile float sCornerRadius = 4;
    public static volatile float sShadowRadius = 10;
    public static volatile float sShadowAlpha = 0.3f;
    public static volatile boolean sAdaptiveColors = true;
    public static volatile boolean sLineWrapping_FabricOnly = true;

    // space between mouse and tooltip
    public static final int TOOLTIP_SPACE = 12;
    public static final int H_BORDER = 4;
    public static final int V_BORDER = 4;
    //public static final int LINE_HEIGHT = 10;
    // extra space after first line
    private static final int TITLE_GAP = 2;

    //private static final List<FormattedText> sTempTexts = new ArrayList<>();

    //private static final int[] sActiveFillColor = new int[4];
    private final int[] mWorkStrokeColor = new int[4];
    private final int[] mActiveStrokeColor = new int[4];
    //static volatile float sAnimationDuration; // milliseconds
    public static volatile int sBorderColorCycle = 1000; // milliseconds

    public static volatile boolean sExactPositioning = true;
    public static volatile boolean sRoundedShapes = true;
    public static volatile boolean sCenterTitle = true;
    public static volatile boolean sTitleBreak = true;
    public static volatile int sArrowScrollFactor = 60;

    public volatile boolean mLayoutRTL;

    private boolean mDraw;
    //public static float sAlpha = 1;

    private float mScroll;
    // 0 = off, 1 = down, -1 = up
    private int mMarqueeDir;
    // the time point when marquee is at top or bottom
    private long mMarqueeEndMillis;

    // arrow key movement
    private int mPendingArrowMove;
    private final ScrollController mScroller = new ScrollController(this);

    private static final long MARQUEE_DELAY_MILLIS = 1200;

    private boolean mFrameGap;
    private long mCurrTimeMillis;
    private long mCurrDeltaMillis;

    // no weak ref, clear on frame gap
    private ItemStack mLastSeenItem;

    // true to use spectrum colors
    private boolean mUseSpectrum;

    // at most one rounded tooltip is allowed per frame
    public GpuBufferSlice mUniforms;
    private int mNumDrawsInThisFrame;

    public TooltipRenderer() {
    }

    public void update(long deltaMillis, long timeMillis) {
        /*if (sAnimationDuration <= 0) {
            sAlpha = 1;
        } else if (sDraw) {
            if (sAlpha < 1) {
                sAlpha = Math.min(sAlpha + deltaMillis / sAnimationDuration, 1);
            }
            sDraw = false;
        } else if (sAlpha > 0) {
            sAlpha = Math.max(sAlpha - deltaMillis / sAnimationDuration, 0);
        }*/
        if (mDraw) {
            mDraw = false;
            if (mFrameGap) {
                mScroller.scrollTo(0);
                mScroller.abortAnimation();
                mMarqueeEndMillis = timeMillis;
                // default is auto scrolling
                mMarqueeDir = 1;
            }
            mFrameGap = false;
        } else {
            mFrameGap = true;
            mLastSeenItem = null;
            mPendingArrowMove = 0;
        }
        mCurrTimeMillis = timeMillis;
        mCurrDeltaMillis = deltaMillis;
        mUniforms = null;
        mNumDrawsInThisFrame = 0;
    }

    public void updateArrowMovement(int move) {
        if (sArrowScrollFactor > 0) {
            mPendingArrowMove += move;
        }
    }

    @Override
    public void modernUI_MC$onScrollAmountUpdated(ScrollController controller, float amount) {
        // stop auto scrolling
        mMarqueeDir = 0;
        mScroll = amount;
    }

    // compute a gradient for the given item
    void computeWorkingColor(@Nonnull ItemStack item) {
        if (sAdaptiveColors && !item.isEmpty()) {
            if (sRoundedShapes && mNumDrawsInThisFrame == 0 &&
                    (item.is(Items.DRAGON_EGG) || item.is(Items.DEBUG_STICK))) {
                mUseSpectrum = true;
                return;
            }
            Style baseStyle = Style.EMPTY;
            Rarity rarity = item.getRarity();
            if (rarity != Rarity.COMMON) {
                baseStyle = MuiModApi.get().applyRarityTo(rarity, baseStyle);
            }
            IntOpenHashSet colors = new IntOpenHashSet(16);
            // consider formatting codes
            StringDecomposer.iterateFormatted(
                    item.getHoverName(),
                    baseStyle,
                    (i, style, ch) -> {
                        TextColor textColor = style.getColor();
                        if (textColor != null) {
                            return !(colors.add(textColor.getValue() & 0xFFFFFF) &&
                                    colors.size() >= 16);
                        }
                        return true;
                    }
            );
            if (!colors.isEmpty()) {
                ArrayList<float[]> hsvColors = new ArrayList<>(16);
                for (var it = colors.iterator(); it.hasNext(); ) {
                    int c = it.nextInt();
                    float[] hsv = new float[3];
                    Color.RGBToHSV(c, hsv);
                    // reduce saturation and brightness
                    hsv[1] = Math.min(hsv[1], 0.9f);
                    hsv[2] = MathUtil.clamp(hsv[2], 0.2f, 0.85f);
                    hsvColors.add(hsv);
                }
                if (!hsvColors.isEmpty()) {
                    int size = hsvColors.size();
                    if (size > 4) {
                        if (sRoundedShapes && mNumDrawsInThisFrame == 0) {
                            mUseSpectrum = true;
                            return;
                        }
                        Collections.shuffle(hsvColors);
                    }
                    int c1 = Color.HSVToColor(hsvColors.get(0));
                    int c2, c3, c4;
                    if (size > 2) {
                        // we have 3 or 4 colors
                        c2 = Color.HSVToColor(hsvColors.get(1));
                        c3 = Color.HSVToColor(hsvColors.get(2));
                        if (size == 4) {
                            c4 = Color.HSVToColor(hsvColors.get(3));
                        } else {
                            // invert hue of c2
                            float[] hsv = hsvColors.get(1);
                            hsv[0] = (hsv[0] + 180f) % 360f;
                            c4 = Color.HSVToColor(hsv);
                        }
                    } else if (size == 2) {
                        // we have two colors, make diagonal
                        c3 = Color.HSVToColor(hsvColors.get(1));
                        c2 = lerpInLinearSpace(0.5f, c1, c3);
                        float[] hsv = new float[3];
                        Color.RGBToHSV(c2, hsv);
                        c4 = adjustColor(hsv, false, true, false, item.isEnchanted());
                    } else {
                        // we have one color...
                        float[] hsv = hsvColors.get(0);
                        boolean mag = item.isEnchanted();
                        c2 = adjustColor(hsv, false, true, false, mag);
                        c3 = adjustColor(hsv, true, true, true, mag);
                        c4 = adjustColor(hsv, true, false, true, mag);
                    }
                    mWorkStrokeColor[0] = (sStrokeColor[0] & 0xFF000000) | c1;
                    mWorkStrokeColor[1] = (sStrokeColor[1] & 0xFF000000) | c2;
                    mWorkStrokeColor[2] = (sStrokeColor[2] & 0xFF000000) | c3;
                    mWorkStrokeColor[3] = (sStrokeColor[3] & 0xFF000000) | c4;
                    mUseSpectrum = false;
                    return;
                }
            }
        }
        System.arraycopy(sStrokeColor, 0, mWorkStrokeColor, 0, 4);
        mUseSpectrum = false;
    }

    static int adjustColor(float[] hsv, boolean hue, boolean sat, boolean val, boolean magnified) {
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];
        if (hue) {
            if (h >= 60f && h <= 240f) {
                h += magnified ? 27f : 15f;
            } else {
                h -= magnified ? 18f : 10f;
            }
            h = (h + 360f) % 360f;
        }
        if (sat) {
            if (s < 0.6f) {
                s += magnified ? 0.18f : 0.12f;
            } else {
                s -= magnified ? 0.12f : 0.06f;
            }
        }
        if (val) {
            if (v < 0.6f) {
                v += magnified ? 0.12f : 0.08f;
            } else {
                v -= magnified ? 0.08f : 0.04f;
            }
        }
        return Color.HSVToColor(h, s, v);
    }

    void updateBorderColor() {
        float p = (mCurrTimeMillis % sBorderColorCycle) / (float) sBorderColorCycle;
        if (mLayoutRTL) {
            int pos = (int) ((mCurrTimeMillis / sBorderColorCycle) & 3);
            for (int i = 0; i < 4; i++) {
                mActiveStrokeColor[i] = lerpInLinearSpace(p,
                        mWorkStrokeColor[(i + pos) & 3],
                        mWorkStrokeColor[(i + pos + 1) & 3]);
            }
        } else {
            int pos = 3 - (int) ((mCurrTimeMillis / sBorderColorCycle) & 3);
            for (int i = 0; i < 4; i++) {
                mActiveStrokeColor[i] = lerpInLinearSpace(p,
                        mWorkStrokeColor[(i + pos) & 3],
                        mWorkStrokeColor[(i + pos + 3) & 3]);
            }
        }
    }

    static int lerpInLinearSpace(float fraction, int startValue, int endValue) {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            float s = ((startValue >> (i << 3)) & 0xff) / 255.0f;
            float t = ((endValue >> (i << 3)) & 0xff) / 255.0f;
            float v = MathUtil.lerp(s, t, fraction);
            result |= Math.round(v * 255.0f) << (i << 3);
        }
        return result;
    }

    // return style if the line contains a single style, or it's too long to determine;
    // return null if the line is empty, or multi style
    @Nullable
    static Style findSingleStyle(@Nonnull ClientTextTooltip line) {
        FormattedCharSequence text = ((AccessClientTextTooltip) line).getText();
        class StyleFinder implements FormattedCharSink {
            Style style = null;
            int count = 0;

            @Override
            public boolean accept(int index, @Nonnull Style style, int codePoint) {
                if (this.style == null) {
                    this.style = style;
                } else if (!CharacterStyle.equalsForTextLayout(this.style, style)) {
                    this.style = null;
                    return false;
                }
                return ++count <= 50;
            }
        }
        var finder = new StyleFinder();
        text.accept(finder);
        return finder.style;
    }

    /*public static void drawTooltip(@Nonnull GLCanvas canvas, @Nonnull List<? extends FormattedText> texts,
                                   @Nonnull Font font, @Nonnull ItemStack stack, @Nonnull PoseStack poseStack,
                                   float mouseX, float mouseY, float preciseMouseX, float preciseMouseY,
                                   int maxTextWidth, float screenWidth, float screenHeight,
                                   int framebufferWidth, int framebufferHeight) {
        sDraw = true;
        final float partialX = (preciseMouseX - (int) preciseMouseX);
        final float partialY = (preciseMouseY - (int) preciseMouseY);

        // matrix transformation for x and y params, compatibility to MineColonies
        float tooltipX = mouseX + TOOLTIP_SPACE + partialX;
        float tooltipY = mouseY - TOOLTIP_SPACE + partialY;
        *//*if (mouseX != (int) mouseX || mouseY != (int) mouseY) {
            // ignore partial pixels
            tooltipX += mouseX - (int) mouseX;
            tooltipY += mouseY - (int) mouseY;
        }*//*
        int tooltipWidth = 0;
        int tooltipHeight = V_BORDER * 2;

        for (FormattedText text : texts) {
            tooltipWidth = Math.max(tooltipWidth, font.width(text));
        }

        boolean needWrap = false;
        if (tooltipX + tooltipWidth + H_BORDER + 1 > screenWidth) {
            tooltipX = mouseX - TOOLTIP_SPACE - H_BORDER - 1 - tooltipWidth + partialX;
            if (tooltipX < H_BORDER + 1) {
                if (mouseX > screenWidth / 2) {
                    tooltipWidth = (int) (mouseX - TOOLTIP_SPACE - H_BORDER * 2 - 2);
                } else {
                    tooltipWidth = (int) (screenWidth - TOOLTIP_SPACE - H_BORDER - 1 - mouseX);
                }
                needWrap = true;
            }
        }

        if (maxTextWidth > 0 && tooltipWidth > maxTextWidth) {
            tooltipWidth = maxTextWidth;
            needWrap = true;
        }

        int titleLinesCount = 1;
        if (needWrap) {
            int w = 0;
            final List<FormattedText> temp = sTempTexts;
            for (int i = 0; i < texts.size(); i++) {
                List<FormattedText> wrapped = font.getSplitter().splitLines(texts.get(i), tooltipWidth, Style.EMPTY);
                if (i == 0) {
                    titleLinesCount = wrapped.size();
                }
                for (FormattedText text : wrapped) {
                    w = Math.max(w, font.width(text));
                    temp.add(text);
                }
            }
            tooltipWidth = w;
            texts = temp;

            if (mouseX > screenWidth / 2) {
                tooltipX = mouseX - TOOLTIP_SPACE - H_BORDER - 1 - tooltipWidth + partialX;
            } else {
                tooltipX = mouseX + TOOLTIP_SPACE + partialX;
            }
        }

        if (texts.size() > 1) {
            tooltipHeight += (texts.size() - 1) * LINE_HEIGHT;
            if (texts.size() > titleLinesCount) {
                tooltipHeight += TITLE_GAP;
            }
        }

        tooltipY = MathUtil.clamp(tooltipY, V_BORDER + 1, screenHeight - tooltipHeight - V_BORDER - 1);

        // smoothing scaled pixels, keep the same partial value as mouse position since tooltipWidth and height are int
        final int tooltipLeft = (int) tooltipX;
        final int tooltipTop = (int) tooltipY;

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        poseStack.pushPose();
        poseStack.translate(0, 0, 400); // because of the order of draw calls, we actually don't need z-shifting
        final Matrix4f mat = poseStack.last().pose();

        final int oldVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);

        // give some points to the original framebuffer, not gui scaled
        canvas.reset(framebufferWidth, framebufferHeight);

        // swap matrices
        RenderSystem.getProjectionMatrix().store(sMatBuf.rewind());
        sMyMat.set(sMatBuf.rewind());
        canvas.setProjection(sMyMat);

        canvas.save();
        RenderSystem.getModelViewMatrix().store(sMatBuf.rewind());
        sMyMat.set(sMatBuf.rewind());
        canvas.multiply(sMyMat);

        mat.store(sMatBuf.rewind()); // Sodium check the remaining
        sMyMat.set(sMatBuf.rewind());
        //myMat.translate(0, 0, -2000);
        canvas.multiply(sMyMat);

        Paint paint = Paint.take();

        paint.setSmoothRadius(0.5f);

        for (int i = 0; i < 4; i++) {
            int color = sFillColor[i];
            int alpha = (int) ((color >>> 24) * sAlpha);
            sColor[i] = (color & 0xFFFFFF) | (alpha << 24);
        }
        paint.setColors(sColor);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(tooltipX - H_BORDER, tooltipY - V_BORDER,
                tooltipX + tooltipWidth + H_BORDER,
                tooltipY + tooltipHeight + V_BORDER, 3, paint);

        for (int i = 0; i < 4; i++) {
            int color = sStrokeColor[i];
            int alpha = (int) ((color >>> 24) * sAlpha);
            sColor[i] = (color & 0xFFFFFF) | (alpha << 24);
        }
        paint.setColors(sColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(tooltipX - H_BORDER, tooltipY - V_BORDER,
                tooltipX + tooltipWidth + H_BORDER,
                tooltipY + tooltipHeight + V_BORDER, 3, paint);
        *//*canvas.drawRoundedFrameT1(tooltipX - H_BORDER, tooltipY - V_BORDER,
                tooltipX + tooltipWidth + H_BORDER, tooltipY + tooltipHeight + V_BORDER, 3);*//*

        canvas.restore();
        canvas.draw(null);

        glBindVertexArray(oldVertexArray);
        glUseProgram(oldProgram);

        final MultiBufferSource.BufferSource source =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final int color = (Math.max((int) (sAlpha * 255), 1) << 24) | 0xFFFFFF;
        for (int i = 0; i < texts.size(); i++) {
            FormattedText text = texts.get(i);
            if (text != null)
                ModernFontRenderer.drawText(text, tooltipX, tooltipY, color, true, mat, source,
                        false, 0, LightCompat.FULL_BRIGHT);
            if (i + 1 == titleLinesCount) {
                tooltipY += TITLE_GAP;
            }
            tooltipY += LINE_HEIGHT;
        }
        source.endBatch();

        // because of the order of draw calls, we actually don't need z-shifting
        poseStack.translate(partialX, partialY, -400);
        // compatibility with Forge mods, like Quark
        *//*MinecraftForge.EVENT_BUS.post(new RenderTooltipEvent.PostText(stack, texts, poseStack, tooltipLeft,
        tooltipTop,
                font, tooltipWidth, tooltipHeight));*//*
        poseStack.popPose();

        RenderSystem.enableDepthTest();
        sTempTexts.clear();
    }*/

    int chooseBorderColor(int corner) {
        if (sBorderColorCycle > 0) {
            return mActiveStrokeColor[corner];
        } else {
            return mWorkStrokeColor[corner];
        }
    }

    Vector4f chooseBorderColor(int corner, Vector4f uniform) {
        int color = chooseBorderColor(corner);
        int a = (color >>> 24);
        int r = ((color >> 16) & 0xff);
        int g = ((color >> 8) & 0xff);
        int b = (color & 0xff);
        return uniform.set(r / 255f, g / 255f, b / 255f, a / 255f);
    }

    public void drawTooltip(@Nonnull ItemStack itemStack, @Nonnull GuiGraphicsExtractor gr,
                            @Nonnull List<ClientTooltipComponent> list, int mouseX, int mouseY,
                            @Nonnull Font font, int screenWidth, int screenHeight,
                            float partialX, float partialY, @Nullable ClientTooltipPositioner positioner,
                            @Nullable Object tooltipStyle) {
        mDraw = true;

        if (itemStack != mLastSeenItem || mNumDrawsInThisFrame > 0) {
            mLastSeenItem = itemStack;
            computeWorkingColor(itemStack);
        }

        int tooltipWidth;
        int tooltipHeight;
        boolean titleGap = false;
        int titleBreakHeight = 0;
        if (list.size() == 1) {
            ClientTooltipComponent component = list.get(0);
            tooltipWidth = component.getWidth(font);
            tooltipHeight = component.getHeight(font) - TITLE_GAP;
        } else {
            tooltipWidth = 0;
            tooltipHeight = 0;
            Style singleStyle = null;
            for (int i = 0; i < list.size(); i++) {
                ClientTooltipComponent component = list.get(i);
                tooltipWidth = Math.max(tooltipWidth, component.getWidth(font));
                int componentHeight = component.getHeight(font);
                tooltipHeight += componentHeight;
                if (i == 0) {
                    titleBreakHeight = componentHeight;
                    if (component instanceof ClientTextTooltip) {
                        if (!itemStack.isEmpty()) {
                            // item stack provided, always add title gap
                            titleGap = true;
                        } else {
                            singleStyle = findSingleStyle((ClientTextTooltip) component);
                            if (singleStyle == null) {
                                // multi-style, add title gap
                                titleGap = true;
                            }
                        }
                    }
                } else if (i <= 2 && !titleGap && component instanceof ClientTextTooltip) {
                    // check first three lines to see if title gap is needed
                    final Style lineStyle = findSingleStyle((ClientTextTooltip) component);
                    if (lineStyle == null) {
                        // multi-style, add title gap
                        titleGap = true;
                    } else if (singleStyle == null) {
                        singleStyle = lineStyle;
                    } else if (!CharacterStyle.equalsForTextLayout(singleStyle, lineStyle)) {
                        // multi-style, add title gap
                        titleGap = true;
                    }
                }
            }
            if (!titleGap) {
                tooltipHeight -= TITLE_GAP;
            }
        }

        float tooltipX;
        float tooltipY;
        final float maxScroll;
        if (positioner != null) {
            var pos = positioner.positionTooltip(screenWidth, screenHeight,
                    mouseX, mouseY,
                    tooltipWidth, tooltipHeight);
            tooltipX = pos.x();
            tooltipY = pos.y();
            maxScroll = 0;
        } else {
            if (mLayoutRTL) {
                tooltipX = mouseX + TOOLTIP_SPACE + partialX - 24 - tooltipWidth;
                if (tooltipX - partialX < 4) {
                    tooltipX += 24 + tooltipWidth;
                }
            } else {
                tooltipX = mouseX + TOOLTIP_SPACE + partialX;
                if (tooltipX - partialX + tooltipWidth + 4 > screenWidth) {
                    tooltipX -= 28 + tooltipWidth;
                }
            }
            partialX = (tooltipX - (int) tooltipX);

            tooltipY = mouseY - TOOLTIP_SPACE + partialY;
            if (tooltipY + tooltipHeight + 6 > screenHeight) {
                tooltipY = screenHeight - tooltipHeight - 6;
            }
            if (tooltipY < 6) {
                tooltipY = 6;
            }
            partialY = (tooltipY - (int) tooltipY);

            maxScroll = 6 + tooltipHeight + 6 - screenHeight;
        }

        if (maxScroll > 0) {
            mScroller.setMaxScroll(maxScroll);
            if (mPendingArrowMove != 0) {
                if (mMarqueeDir != 0) {
                    mScroller.scrollTo(mScroll);
                    mScroller.abortAnimation();
                }
                mScroller.scrollBy(mPendingArrowMove * sArrowScrollFactor);
                mPendingArrowMove = 0;
            }
            mScroller.update(MuiModApi.getElapsedTime());

            mScroll = MathUtil.clamp(mScroll, 0, maxScroll);

            if (mMarqueeDir != 0 && mCurrTimeMillis - mMarqueeEndMillis >= MARQUEE_DELAY_MILLIS) {
                float t = MathUtil.clamp(0.5f * tooltipWidth / screenWidth, 0.0f, 0.5f);
                float baseMultiplier = (1.5f - t) * 0.01f;
                mScroll += mMarqueeDir * mCurrDeltaMillis *
                        (baseMultiplier + baseMultiplier * Math.min(maxScroll / screenHeight, 1.5f));
                if (mMarqueeDir > 0) {
                    if (mScroll >= maxScroll) {
                        mMarqueeDir = -1;
                        mMarqueeEndMillis = mCurrTimeMillis;
                    }
                } else {
                    if (mScroll <= 0) {
                        mMarqueeDir = 1;
                        mMarqueeEndMillis = mCurrTimeMillis;
                    }
                }
            }
        } else {
            mScroll = 0;
            mPendingArrowMove = 0;
        }

        if (sBorderColorCycle > 0) {
            updateBorderColor();
        }

        gr.pose().pushMatrix();
        gr.pose().translate(0, -mScroll);

        if (tooltipStyle == null) {
            // make an immutable copy and shared by multiple calls
            final Matrix3x2f pose = new Matrix3x2f(gr.pose());
            final ScreenRectangle scissor = MuiModApi.get().peekScissorStack(gr);
            if (sRoundedShapes && mNumDrawsInThisFrame == 0) {
                drawRoundedBackground(gr, pose, scissor,
                        tooltipX, tooltipY, tooltipWidth, tooltipHeight,
                        titleGap, titleBreakHeight);
            } else {
                drawVanillaBackground(gr, pose, scissor,
                        tooltipX, tooltipY, tooltipWidth, tooltipHeight,
                        titleGap, titleBreakHeight);
            }
        }
        // To handle multiple calls or recursive calls.
        // Subsequent operations use only variables on the stack instead of member variables.
        ++mNumDrawsInThisFrame;

        final int drawX = (int) tooltipX;
        int drawY = (int) tooltipY;









        gr.pose().translate(partialX, partialY);
        if (tooltipStyle != null) {
            TooltipRenderUtilCompat.renderTooltipBackground(gr, drawX, drawY, tooltipWidth, tooltipHeight, tooltipStyle);
        }
        for (int i = 0; i < list.size(); i++) {
            ClientTooltipComponent component = list.get(i);
            if (titleGap && i == 0 && sCenterTitle) {
                component.extractText(gr, font, drawX + (tooltipWidth - component.getWidth(font)) / 2, drawY);
            } else if (mLayoutRTL) {
                component.extractText(gr, font, drawX + tooltipWidth - component.getWidth(font), drawY);
            } else {
                component.extractText(gr, font, drawX, drawY);
            }
            if (titleGap && i == 0) {
                drawY += TITLE_GAP;
            }
            drawY += component.getHeight(font);
        }


        drawY = (int) tooltipY;

        for (int i = 0; i < list.size(); i++) {
            ClientTooltipComponent component = list.get(i);
            if (mLayoutRTL) {
                component.extractImage(font, drawX + tooltipWidth - component.getWidth(font), drawY, tooltipWidth, tooltipHeight, gr);
            } else {
                component.extractImage(font, drawX, drawY, tooltipWidth, tooltipHeight, gr);
            }
            if (titleGap && i == 0) {
                drawY += TITLE_GAP;
            }
            drawY += component.getHeight(font);
        }
        gr.pose().popMatrix();
    }


    //TODO 圆角不渲染不知道怎么改，方形没问题 @Fodoth_jinzi89
    private void drawRoundedBackground(@Nonnull GuiGraphicsExtractor gr, Matrix3x2f pose,
                                       ScreenRectangle scissor,
                                       float tooltipX, float tooltipY,
                                       int tooltipWidth, int tooltipHeight,
                                       boolean titleGap, int titleBreakHeight) {
        float halfWidth = tooltipWidth / 2f;
        float halfHeight = tooltipHeight / 2f;
        float centerX = tooltipX + halfWidth;
        float centerY = tooltipY + halfHeight;
        float sizeX = halfWidth + H_BORDER;
        float sizeY = halfHeight + V_BORDER;
        float shadowRadius = Math.max(sShadowRadius, 0.00001f);
        float left = tooltipX - H_BORDER;
        float top = tooltipY - V_BORDER;
        float right = tooltipX + tooltipWidth + H_BORDER;
        float bottom = tooltipY + tooltipHeight + V_BORDER;

        float radius = Math.min(sCornerRadius, Math.min(sizeX, sizeY));
        drawRoundedFill(gr, pose, scissor, left, top, right, bottom, radius);

        Vector4f pushData0 = new Vector4f(sizeX, sizeY, sCornerRadius, sBorderWidth / 2f);
        float rainbowOffset = 0;
        if (mUseSpectrum) {
            rainbowOffset = 1;
            if (sBorderColorCycle > 0) {
                long overallCycle = sBorderColorCycle * 4L;
                rainbowOffset += (float) (mCurrTimeMillis % overallCycle) / overallCycle;
            }
            if (!mLayoutRTL) {
                rainbowOffset = -rainbowOffset;
            }
        }
        Vector3f pushData1 = new Vector3f(sShadowAlpha, 1.25f / shadowRadius, (sFillColor[0] >>> 24) / 255f);
        Matrix4f colorMatrix = new Matrix4f();
        if (rainbowOffset == 0) {
            Vector4f colorColumn = new Vector4f();
            colorMatrix.setColumn(0, chooseBorderColor(0, colorColumn));
            colorMatrix.setColumn(1, chooseBorderColor(1, colorColumn));
            colorMatrix.setColumn(2, chooseBorderColor(3, colorColumn));
            colorMatrix.setColumn(3, chooseBorderColor(2, colorColumn));
        } else {
            colorMatrix.zero();
        }
        // 1.21.11 dropped the extra float parameter in DynamicUniforms#writeTransform.
        // Store u_RainbowOffset in u_PushData2.w (alpha is unused by the shader otherwise).
        colorMatrix.m03(rainbowOffset);

        // we expect local coordinates, concat pose with model view
        Matrix3x2f localMatrix = new Matrix3x2f(pose);
        localMatrix.translate(centerX, centerY);

        mUniforms = DynamicUniformsCompat.writeTransform(
                RenderSystem.getDynamicUniforms(),
                new Matrix4f().mul(localMatrix),
                pushData0,
                pushData1,
                colorMatrix,
                rainbowOffset
        );
        // estimate the draw bounds, half stroke width + 0.5 AA bloat + shadow spread
        float extent = sBorderWidth / 2f + 0.5f + shadowRadius * 1.2f;
        float extentX = sizeX + extent;
        float extentY = sizeY + extent;
        ScreenRectangle bounds = GradientRectangleRenderState.getBounds(
                -extentX, -extentY, extentX, extentY,
                localMatrix, scissor
        );

        MuiModApi.get().submitGuiElementRenderState(gr,
                new GradientRectangleRenderState(
                        GuiRenderType.PIPELINE_TOOLTIP,
                        TextureSetup.noTexture(),
                        new Matrix3x2f(),
                        -extentX, -extentY, extentX, extentY,
                        sFillColor[0], sFillColor[1], sFillColor[2], sFillColor[3],
                        scissor,
                        bounds
                ));

        drawRoundedStroke(gr, pose, scissor, left, top, right, bottom,
                radius, Math.max(1.0f, sBorderWidth));

        if (titleGap && sTitleBreak) {
            fillGrad(gr, pose, scissor,
                    tooltipX, tooltipY + titleBreakHeight - 0.5f,
                    tooltipX + tooltipWidth, tooltipY + titleBreakHeight + 0.5f,
                    0xE0C8C8C8, 0xE0C8C8C8, 0xE0C8C8C8, 0xE0C8C8C8);
        }
    }

    private void drawRoundedStroke(@Nonnull GuiGraphicsExtractor gr, Matrix3x2f pose,
                                   ScreenRectangle scissor,
                                   float left, float top, float right, float bottom,
                                   float radius, float width) {
        if (!(left < right && top < bottom) || width <= 0.0f) {
            return;
        }
        MuiModApi.get().submitGuiElementRenderState(gr,
                new RoundedStrokeRenderState(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        new Matrix3x2f(pose),
                        left, top, right, bottom,
                        radius, width,
                        chooseBorderColor(0), chooseBorderColor(1),
                        chooseBorderColor(2), chooseBorderColor(3),
                        scissor
                ));
    }

    private void drawRoundedFill(@Nonnull GuiGraphicsExtractor gr, Matrix3x2f pose,
                                 ScreenRectangle scissor,
                                 float left, float top, float right, float bottom,
                                 float radius) {
        if (!(left < right && top < bottom)) {
            return;
        }
        radius = Math.min(radius, Math.min((right - left) * 0.5f, (bottom - top) * 0.5f));
        if (radius <= 0.5f) {
            fillGrad(gr, pose, scissor, left, top, right, bottom,
                    sFillColor[0], sFillColor[1], sFillColor[2], sFillColor[3]);
            return;
        }

        float middleTop = top + radius;
        float middleBottom = bottom - radius;
        if (middleTop < middleBottom) {
            fillGrad(gr, pose, scissor, left, middleTop, right, middleBottom,
                    sFillColor[0], sFillColor[1], sFillColor[2], sFillColor[3]);
        }

        int steps = Math.max(1, (int) Math.ceil(radius));
        float step = radius / steps;
        for (int i = 0; i < steps; i++) {
            float y0 = top + i * step;
            float y1 = i + 1 == steps ? middleTop : y0 + step;
            float dy = radius - ((y0 + y1) * 0.5f - top);
            float inset = radius - (float) Math.sqrt(Math.max(0.0f, radius * radius - dy * dy));
            fillGrad(gr, pose, scissor, left + inset, y0, right - inset, y1,
                    sFillColor[0], sFillColor[1], sFillColor[2], sFillColor[3]);

            y0 = middleBottom + i * step;
            y1 = i + 1 == steps ? bottom : y0 + step;
            dy = (y0 + y1) * 0.5f - middleBottom;
            inset = radius - (float) Math.sqrt(Math.max(0.0f, radius * radius - dy * dy));
            fillGrad(gr, pose, scissor, left + inset, y0, right - inset, y1,
                    sFillColor[0], sFillColor[1], sFillColor[2], sFillColor[3]);
        }
    }

    private void drawVanillaBackground(@Nonnull GuiGraphicsExtractor gr, Matrix3x2f pose,
                                       ScreenRectangle scissor,
                                       float tooltipX, float tooltipY,
                                       int tooltipWidth, int tooltipHeight,
                                       boolean titleGap, int titleBreakHeight) {
        float left = tooltipX - H_BORDER;
        float top = tooltipY - V_BORDER;
        float right = tooltipX + tooltipWidth + H_BORDER;
        float bottom = tooltipY + tooltipHeight + V_BORDER;

        // top
        fillGrad(gr, pose, scissor, left, top - 1, right, top,
                sFillColor[0], sFillColor[1], sFillColor[1], sFillColor[0]);
        // bottom
        fillGrad(gr, pose, scissor, left, bottom, right, bottom + 1,
                sFillColor[3], sFillColor[2], sFillColor[2], sFillColor[3]);
        // center
        fillGrad(gr, pose, scissor, left, top, right, bottom,
                sFillColor[0], sFillColor[1], sFillColor[2], sFillColor[3]);
        // left
        fillGrad(gr, pose, scissor, left - 1, top, left, bottom,
                sFillColor[0], sFillColor[0], sFillColor[3], sFillColor[3]);
        // right
        fillGrad(gr, pose, scissor, right, top, right + 1, bottom,
                sFillColor[1], sFillColor[1], sFillColor[2], sFillColor[2]);

        if (titleGap && sTitleBreak) {
            fillGrad(gr, pose, scissor,
                    tooltipX, tooltipY + titleBreakHeight - 0.5f,
                    tooltipX + tooltipWidth, tooltipY + titleBreakHeight + 0.5f,
                    0xE0C8C8C8, 0xE0C8C8C8, 0xE0C8C8C8, 0xE0C8C8C8);
        }

        // top
        fillGrad(gr, pose, scissor,
                left, top, right, top + 1,
                chooseBorderColor(0), chooseBorderColor(1),
                chooseBorderColor(1), chooseBorderColor(0));
        // right
        fillGrad(gr, pose, scissor,
                right - 1, top, right, bottom,
                chooseBorderColor(1), chooseBorderColor(1),
                chooseBorderColor(2), chooseBorderColor(2));
        // bottom
        fillGrad(gr, pose, scissor,
                left, bottom - 1, right, bottom,
                chooseBorderColor(3), chooseBorderColor(2),
                chooseBorderColor(2), chooseBorderColor(3));
        // left
        fillGrad(gr, pose, scissor,
                left, top, left + 1, bottom,
                chooseBorderColor(0), chooseBorderColor(0),
                chooseBorderColor(3), chooseBorderColor(3));
    }

    private static void fillGrad(GuiGraphicsExtractor gr, Matrix3x2f pose, ScreenRectangle scissor,
                                 float left, float top, float right, float bottom,
                                 int colorUL, int colorUR, int colorLR, int colorLL) {
        MuiModApi.get().submitGuiElementRenderState(gr,
                new GradientRectangleRenderState(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        pose,
                        left, top, right, bottom,
                        colorUL, colorUR, colorLR, colorLL,
                        scissor
        ));
    }

    private record RoundedStrokeRenderState(
            @Nonnull RenderPipeline pipeline,
            @Nonnull TextureSetup textureSetup,
            @Nonnull Matrix3x2f pose,
            float left, float top, float right, float bottom,
            float radius, float width,
            int colorTL, int colorTR, int colorBR, int colorBL,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds
    ) implements GuiElementRenderState {

        private static final float AA = 0.75f;

        RoundedStrokeRenderState(@Nonnull RenderPipeline pipeline,
                                 @Nonnull TextureSetup textureSetup,
                                 @Nonnull Matrix3x2f pose,
                                 float left, float top, float right, float bottom,
                                 float radius, float width,
                                 int colorTL, int colorTR, int colorBR, int colorBL,
                                 @Nullable ScreenRectangle scissorArea) {
            this(pipeline, textureSetup, pose,
                    left, top, right, bottom,
                    radius, width,
                    colorTL, colorTR, colorBR, colorBL,
                    scissorArea,
                    GradientRectangleRenderState.getBounds(
                            left - AA, top - AA, right + AA, bottom + AA,
                            pose, scissorArea
                    ));
        }

        @Override
        public void buildVertices(@Nonnull VertexConsumer consumer) {
            float maxRadius = Math.min((right - left) * 0.5f, (bottom - top) * 0.5f);
            float r = Math.min(radius, maxRadius);
            float w = Math.min(width, maxRadius);
            if (r <= 0.5f) {
                addRectStroke(consumer, left, top, right, bottom, w);
                return;
            }

            float innerRadius = Math.max(r - w, 0.0f);
            float x0 = left + r;
            float x1 = right - r;
            float y0 = top + r;
            float y1 = bottom - r;

            addHorizontalEdge(consumer, x0, x1, top, w, false, colorTL, colorTR);
            addHorizontalEdge(consumer, x0, x1, bottom, w, true, colorBL, colorBR);
            addVerticalEdge(consumer, left, y0, y1, w, false, colorTL, colorBL);
            addVerticalEdge(consumer, right, y0, y1, w, true, colorTR, colorBR);

            int segments = Math.max(8, (int) Math.ceil(r * 3.0f));
            addCorner(consumer, left + r, top + r, (float) Math.PI, 1.5f * (float) Math.PI,
                    innerRadius, r, colorTL, segments);
            addCorner(consumer, right - r, top + r, 1.5f * (float) Math.PI, 2.0f * (float) Math.PI,
                    innerRadius, r, colorTR, segments);
            addCorner(consumer, right - r, bottom - r, 0.0f, 0.5f * (float) Math.PI,
                    innerRadius, r, colorBR, segments);
            addCorner(consumer, left + r, bottom - r, 0.5f * (float) Math.PI, (float) Math.PI,
                    innerRadius, r, colorBL, segments);
        }

        private void addRectStroke(VertexConsumer consumer,
                                   float l, float t, float r, float b, float w) {
            addHorizontalEdge(consumer, l, r, t, w, false, colorTL, colorTR);
            addHorizontalEdge(consumer, l, r, b, w, true, colorBL, colorBR);
            addVerticalEdge(consumer, l, t, b, w, false, colorTL, colorBL);
            addVerticalEdge(consumer, r, t, b, w, true, colorTR, colorBR);
        }

        private void addHorizontalEdge(VertexConsumer consumer,
                                       float x0, float x1, float y, float w,
                                       boolean bottomEdge, int leftColor, int rightColor) {
            if (!(x0 < x1)) {
                return;
            }
            if (bottomEdge) {
                addQuad(consumer, x0, y, x1, y, x1, y + AA, x0, y + AA,
                        leftColor, rightColor, withAlpha(rightColor, 0.0f), withAlpha(leftColor, 0.0f));
                addQuad(consumer, x0, y - w, x1, y - w, x1, y, x0, y,
                        leftColor, rightColor, rightColor, leftColor);
                addQuad(consumer, x0, y - w - AA, x1, y - w - AA, x1, y - w, x0, y - w,
                        withAlpha(leftColor, 0.0f), withAlpha(rightColor, 0.0f), rightColor, leftColor);
            } else {
                addQuad(consumer, x0, y - AA, x1, y - AA, x1, y, x0, y,
                        withAlpha(leftColor, 0.0f), withAlpha(rightColor, 0.0f), rightColor, leftColor);
                addQuad(consumer, x0, y, x1, y, x1, y + w, x0, y + w,
                        leftColor, rightColor, rightColor, leftColor);
                addQuad(consumer, x0, y + w, x1, y + w, x1, y + w + AA, x0, y + w + AA,
                        leftColor, rightColor, withAlpha(rightColor, 0.0f), withAlpha(leftColor, 0.0f));
            }
        }

        private void addVerticalEdge(VertexConsumer consumer,
                                     float x, float y0, float y1, float w,
                                     boolean rightEdge, int topColor, int bottomColor) {
            if (!(y0 < y1)) {
                return;
            }
            if (rightEdge) {
                addQuad(consumer, x, y0, x + AA, y0, x + AA, y1, x, y1,
                        topColor, withAlpha(topColor, 0.0f), withAlpha(bottomColor, 0.0f), bottomColor);
                addQuad(consumer, x - w, y0, x, y0, x, y1, x - w, y1,
                        topColor, topColor, bottomColor, bottomColor);
                addQuad(consumer, x - w - AA, y0, x - w, y0, x - w, y1, x - w - AA, y1,
                        withAlpha(topColor, 0.0f), topColor, bottomColor, withAlpha(bottomColor, 0.0f));
            } else {
                addQuad(consumer, x - AA, y0, x, y0, x, y1, x - AA, y1,
                        withAlpha(topColor, 0.0f), topColor, bottomColor, withAlpha(bottomColor, 0.0f));
                addQuad(consumer, x, y0, x + w, y0, x + w, y1, x, y1,
                        topColor, topColor, bottomColor, bottomColor);
                addQuad(consumer, x + w, y0, x + w + AA, y0, x + w + AA, y1, x + w, y1,
                        topColor, withAlpha(topColor, 0.0f), withAlpha(bottomColor, 0.0f), bottomColor);
            }
        }

        private void addCorner(VertexConsumer consumer,
                               float cx, float cy, float start, float end,
                               float innerRadius, float outerRadius,
                               int color, int segments) {
            addCornerRing(consumer, cx, cy, start, end,
                    outerRadius + AA, outerRadius,
                    withAlpha(color, 0.0f), color, segments);
            if (innerRadius < outerRadius) {
                addCornerRing(consumer, cx, cy, start, end,
                        outerRadius, innerRadius,
                        color, color, segments);
            }
            if (innerRadius > 0.0f) {
                addCornerRing(consumer, cx, cy, start, end,
                        innerRadius, Math.max(innerRadius - AA, 0.0f),
                        color, withAlpha(color, 0.0f), segments);
            }
        }

        private void addCornerRing(VertexConsumer consumer,
                                   float cx, float cy, float start, float end,
                                   float outerRadius, float innerRadius,
                                   int outerColor, int innerColor,
                                   int segments) {
            float step = (end - start) / segments;
            for (int i = 0; i < segments; i++) {
                float a0 = start + i * step;
                float a1 = i + 1 == segments ? end : a0 + step;
                float ox0 = cx + (float) Math.cos(a0) * outerRadius;
                float oy0 = cy + (float) Math.sin(a0) * outerRadius;
                float ox1 = cx + (float) Math.cos(a1) * outerRadius;
                float oy1 = cy + (float) Math.sin(a1) * outerRadius;
                float ix1 = cx + (float) Math.cos(a1) * innerRadius;
                float iy1 = cy + (float) Math.sin(a1) * innerRadius;
                float ix0 = cx + (float) Math.cos(a0) * innerRadius;
                float iy0 = cy + (float) Math.sin(a0) * innerRadius;
                addQuad(consumer, ox0, oy0, ox1, oy1, ix1, iy1, ix0, iy0,
                        outerColor, outerColor, innerColor, innerColor);
            }
        }

        private void addQuad(VertexConsumer consumer,
                             float x0, float y0, float x1, float y1,
                             float x2, float y2, float x3, float y3,
                             int c0, int c1, int c2, int c3) {
            consumer.addVertexWith2DPose(pose, x2, y2).setColor(c2);
            consumer.addVertexWith2DPose(pose, x1, y1).setColor(c1);
            consumer.addVertexWith2DPose(pose, x0, y0).setColor(c0);
            consumer.addVertexWith2DPose(pose, x3, y3).setColor(c3);
        }

        private static int withAlpha(int color, float alphaScale) {
            int alpha = Math.round(((color >>> 24) & 0xff) * alphaScale);
            return (color & 0x00FFFFFF) | (MathUtil.clamp(alpha, 0, 255) << 24);
        }
    }
}
