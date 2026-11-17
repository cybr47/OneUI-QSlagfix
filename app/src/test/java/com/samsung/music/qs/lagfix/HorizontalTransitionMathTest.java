package com.samsung.music.qs.lagfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import com.samsung.music.qs.lagfix.HorizontalTransitionMath.FrameTransform;
import org.junit.Test;

/**
 * Unit tests verifying the GlassUI-style horizontal transition math and {@link HorizontalSwipeSession}
 * lifecycle on the {@code cust} branch:
 * <ul>
 *   <li>Both swipe directions in normal and reversed panel orders
 *   <li>Symmetric {@link HorizontalTransitionMath#FADE_CUTOFF} ({@code 0.45f}) outgoing opacity
 *       curve and immediate incoming visibility ({@code alpha = 1.0f})
 *   <li>Mid-drag hold and finger reversal (restoring outgoing opacity and reversing translations)
 *   <li>Cancelled swipe returning to the starting page and fast fling completing to target page
 *   <li>Session scoping: cleanup is a complete no-op when no horizontal swipe is active, and
 *       modified ancestor/Compose-child/panel views restore their exact prior values when an
 *       active horizontal swipe ends
 *   <li>Single Compose {@code slideFraction} pin per horizontal swipe session (avoiding two writes
 *       per frame)
 * </ul>
 */
public final class HorizontalTransitionMathTest {
    private static final float EPS = 1e-4f;
    private static final float WIDTH = 1080.0f;

    private static final class FakeNode {
        int visibility;
        float alpha;
        float translationX;
        float translationY;
        float scaleX;
        float scaleY;
        int writeCount;

        FakeNode(int visibility, float alpha, float translationX, float translationY, float scaleX, float scaleY) {
            this.visibility = visibility;
            this.alpha = alpha;
            this.translationX = translationX;
            this.translationY = translationY;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    private static final HorizontalSwipeSession.PropertyAccess<FakeNode> FAKE_ACCESS =
            new HorizontalSwipeSession.PropertyAccess<FakeNode>() {
                @Override
                public int getVisibility(FakeNode target) {
                    return target.visibility;
                }

                @Override
                public void setVisibility(FakeNode target, Object animWrapper, int visibility) {
                    target.visibility = visibility;
                    target.writeCount++;
                }

                @Override
                public float getAlpha(FakeNode target) {
                    return target.alpha;
                }

                @Override
                public void setAlpha(FakeNode target, Object animWrapper, float alpha) {
                    target.alpha = alpha;
                    target.writeCount++;
                }

                @Override
                public float getTranslationX(FakeNode target) {
                    return target.translationX;
                }

                @Override
                public void setTranslationX(FakeNode target, Object animWrapper, float translationX) {
                    target.translationX = translationX;
                    target.writeCount++;
                }

                @Override
                public float getTranslationY(FakeNode target) {
                    return target.translationY;
                }

                @Override
                public void setTranslationY(FakeNode target, Object animWrapper, float translationY) {
                    target.translationY = translationY;
                    target.writeCount++;
                }

                @Override
                public float getScaleX(FakeNode target) {
                    return target.scaleX;
                }

                @Override
                public void setScaleX(FakeNode target, Object animWrapper, float scaleX) {
                    target.scaleX = scaleX;
                    target.writeCount++;
                }

                @Override
                public float getScaleY(FakeNode target) {
                    return target.scaleY;
                }

                @Override
                public void setScaleY(FakeNode target, Object animWrapper, float scaleY) {
                    target.scaleY = scaleY;
                    target.writeCount++;
                }
            };

    @Test
    public void fadeCutoffIsNamedAt045AndSymmetricInBothDirections() {
        assertEquals(0.45f, HorizontalTransitionMath.FADE_CUTOFF, EPS);

        assertEquals(1.0f, HorizontalTransitionMath.outgoingAlpha(0.0f), EPS);
        assertTrue(HorizontalTransitionMath.outgoingAlpha(0.01f) < 1.0f);
        assertEquals(0.5f, HorizontalTransitionMath.outgoingAlpha(0.225f), EPS);
        assertEquals(1.0f - (0.40f / 0.45f), HorizontalTransitionMath.outgoingAlpha(0.40f), EPS);
        assertEquals(0.0f, HorizontalTransitionMath.outgoingAlpha(0.45f), EPS);
        assertEquals(0.0f, HorizontalTransitionMath.outgoingAlpha(0.75f), EPS);
        assertEquals(0.0f, HorizontalTransitionMath.outgoingAlpha(1.0f), EPS);

        for (float p : new float[]{0.0f, 0.01f, 0.2f, 0.45f, 0.8f, 1.0f}) {
            assertEquals(1.0f, HorizontalTransitionMath.incomingAlpha(p), EPS);
            assertEquals(
                    HorizontalTransitionMath.outgoingAlpha(p),
                    HorizontalTransitionMath.notificationsAlpha(p, true),
                    EPS);
            assertEquals(
                    HorizontalTransitionMath.outgoingAlpha(p),
                    HorizontalTransitionMath.qsAlpha(p, false),
                    EPS);
            assertEquals(1.0f, HorizontalTransitionMath.qsAlpha(p, true), EPS);
            assertEquals(1.0f, HorizontalTransitionMath.notificationsAlpha(p, false), EPS);
        }
    }

    @Test
    public void normalOrderSwipeLeftMovesNotificationsLeftAndBringsQsFromRight() {
        FrameTransform start = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.0f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertTrue(start.activeHorizontal);
        assertTrue(start.movingToQs);
        assertEquals(0.0f, start.notificationsTranslationX, EPS);
        assertEquals(+WIDTH, start.qsTranslationX, EPS);
        assertEquals(1.0f, start.notificationsAlpha, EPS);
        assertEquals(1.0f, start.qsAlpha, EPS);
        assertEquals(0.0f, start.composeSlideFraction, EPS);

        FrameTransform midFade = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.225f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(-0.225f * WIDTH, midFade.notificationsTranslationX, EPS);
        assertEquals(+0.775f * WIDTH, midFade.qsTranslationX, EPS);
        assertEquals(0.5f, midFade.notificationsAlpha, EPS);
        assertEquals(1.0f, midFade.qsAlpha, EPS);

        FrameTransform atCutoff = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.45f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(-0.45f * WIDTH, atCutoff.notificationsTranslationX, EPS);
        assertEquals(+0.55f * WIDTH, atCutoff.qsTranslationX, EPS);
        assertEquals(0.0f, atCutoff.notificationsAlpha, EPS);
        assertEquals(1.0f, atCutoff.qsAlpha, EPS);

        FrameTransform postCutoff = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.80f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(-0.80f * WIDTH, postCutoff.notificationsTranslationX, EPS);
        assertEquals(+0.20f * WIDTH, postCutoff.qsTranslationX, EPS);
        assertEquals(0.0f, postCutoff.notificationsAlpha, EPS);
        assertEquals(1.0f, postCutoff.qsAlpha, EPS);
    }

    @Test
    public void normalOrderSwipeRightMovesQsRightAndBringsNotificationsFromLeft() {
        FrameTransform midFade = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.225f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                false);
        assertTrue(midFade.activeHorizontal);
        assertFalse(midFade.movingToQs);
        assertEquals(+0.225f * WIDTH, midFade.qsTranslationX, EPS);
        assertEquals(-0.775f * WIDTH, midFade.notificationsTranslationX, EPS);
        assertEquals(0.5f, midFade.qsAlpha, EPS);
        assertEquals(1.0f, midFade.notificationsAlpha, EPS);

        FrameTransform atCutoff = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.45f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                false);
        assertEquals(+0.45f * WIDTH, atCutoff.qsTranslationX, EPS);
        assertEquals(-0.55f * WIDTH, atCutoff.notificationsTranslationX, EPS);
        assertEquals(0.0f, atCutoff.qsAlpha, EPS);
        assertEquals(1.0f, atCutoff.notificationsAlpha, EPS);
    }

    @Test
    public void reversedPanelOrderMirrorsTranslationDirections() {
        FrameTransform toQs = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.30f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                true);
        assertTrue(toQs.movingToQs);
        assertEquals(+0.30f * WIDTH, toQs.notificationsTranslationX, EPS);
        assertEquals(-0.70f * WIDTH, toQs.qsTranslationX, EPS);
        assertEquals(HorizontalTransitionMath.outgoingAlpha(0.30f), toQs.notificationsAlpha, EPS);
        assertEquals(1.0f, toQs.qsAlpha, EPS);

        FrameTransform toShade = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.30f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                true);
        assertFalse(toShade.movingToQs);
        assertEquals(-0.30f * WIDTH, toShade.qsTranslationX, EPS);
        assertEquals(+0.70f * WIDTH, toShade.notificationsTranslationX, EPS);
        assertEquals(HorizontalTransitionMath.outgoingAlpha(0.30f), toShade.qsAlpha, EPS);
        assertEquals(1.0f, toShade.notificationsAlpha, EPS);
    }

    @Test
    public void midDragHoldAndReversalRestoresOutgoingOpacityAndMovesPagesBackTogether() {
        FrameTransform dragOut = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.50f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        FrameTransform hold = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.50f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(dragOut.qsTranslationX, hold.qsTranslationX, EPS);
        assertEquals(dragOut.notificationsTranslationX, hold.notificationsTranslationX, EPS);
        assertEquals(0.0f, hold.notificationsAlpha, EPS);
        assertEquals(1.0f, hold.qsAlpha, EPS);

        FrameTransform reverseMid = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.225f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(WIDTH, reverseMid.qsTranslationX - reverseMid.notificationsTranslationX, EPS);
        assertEquals(-0.225f * WIDTH, reverseMid.notificationsTranslationX, EPS);
        assertEquals(+0.775f * WIDTH, reverseMid.qsTranslationX, EPS);
        assertEquals(0.50f, reverseMid.notificationsAlpha, EPS);
        assertEquals(1.0f, reverseMid.qsAlpha, EPS);

        FrameTransform reverseNearStart = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.09f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(0.80f, reverseNearStart.notificationsAlpha, EPS);
        assertEquals(-0.09f * WIDTH, reverseNearStart.notificationsTranslationX, EPS);
        assertEquals(+0.91f * WIDTH, reverseNearStart.qsTranslationX, EPS);
    }

    @Test
    public void cancelledSwipeAndFastFlingSettleComposeSlideFractionAndResetTranslations() {
        FrameTransform settledShade = FrameTransform.forSlide(
                HorizontalTransitionMath.SHADE_STATE,
                0.0f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertFalse(settledShade.activeHorizontal);
        assertEquals(0.0f, settledShade.qsTranslationX, EPS);
        assertEquals(0.0f, settledShade.notificationsTranslationX, EPS);
        assertEquals(
                HorizontalTransitionMath.COMPOSE_SLIDE_FRACTION_HIDDEN,
                settledShade.composeSlideFraction,
                EPS);

        float[] flingFrames = new float[]{0.15f, 0.48f, 0.82f, 1.0f};
        float previousQsTx = WIDTH;
        for (float f : flingFrames) {
            FrameTransform frame = FrameTransform.forSlide(
                    HorizontalTransitionMath.TRANSITION_STATE,
                    f,
                    0.0f,
                    HorizontalTransitionMath.DIRECTION_LEFT,
                    HorizontalTransitionMath.QS_STATE,
                    WIDTH,
                    false);
            assertTrue(frame.activeHorizontal);
            assertTrue(frame.qsTranslationX < previousQsTx);
            assertEquals(WIDTH, frame.qsTranslationX - frame.notificationsTranslationX, EPS);
            assertEquals(
                    HorizontalTransitionMath.COMPOSE_SLIDE_FRACTION_VISIBLE,
                    frame.composeSlideFraction,
                    EPS);
            previousQsTx = frame.qsTranslationX;
        }

        FrameTransform settledQs = FrameTransform.forSlide(
                HorizontalTransitionMath.QS_STATE,
                1.0f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertFalse(settledQs.activeHorizontal);
        assertEquals(0.0f, settledQs.qsTranslationX, EPS);
        assertEquals(0.0f, settledQs.notificationsTranslationX, EPS);
        assertEquals(
                HorizontalTransitionMath.COMPOSE_SLIDE_FRACTION_VISIBLE,
                settledQs.composeSlideFraction,
                EPS);
    }

    @Test
    public void inactiveSessionDoesNotTouchViewsAndActiveSessionRestoresActualPriorValues() {
        HorizontalSwipeSession session = new HorizontalSwipeSession();
        FakeNode qsRoot = new FakeNode(View.INVISIBLE, 0.0f, 0.0f, 12.0f, 0.94f, 0.94f);
        FakeNode ancestor = new FakeNode(View.INVISIBLE, 0.35f, -24.0f, 8.0f, 0.98f, 0.98f);
        FakeNode healthyAncestor = new FakeNode(View.VISIBLE, 1.0f, 0.0f, 4.0f, 1.0f, 1.0f);
        FakeNode composeChild = new FakeNode(View.INVISIBLE, 0.25f, 15.0f, 0.0f, 1.0f, 1.0f);

        // When no horizontal swipe is active, restoreAndEnd is a complete no-op.
        assertFalse(session.isActive());
        session.restoreAndEnd(FAKE_ACCESS);
        assertEquals(0, qsRoot.writeCount);
        assertEquals(0, ancestor.writeCount);

        // Begin an active horizontal swipe session.
        session.begin();
        assertTrue(session.isActive());

        // Healthy ancestors already VISIBLE with alpha=1f and translationX=0f are not touched at all.
        session.ensureDrawable(healthyAncestor, FAKE_ACCESS);
        assertEquals(0, healthyAncestor.writeCount);
        assertEquals(0, session.savedViewCount());

        // Non-drawable ancestors and Compose children have their exact prior values saved before mutation.
        session.ensureDrawable(ancestor, FAKE_ACCESS);
        session.ensureDrawable(composeChild, FAKE_ACCESS);
        assertEquals(View.VISIBLE, ancestor.visibility);
        assertEquals(1.0f, ancestor.alpha, EPS);
        assertEquals(0.0f, ancestor.translationX, EPS);
        assertEquals(View.VISIBLE, composeChild.visibility);
        assertEquals(1.0f, composeChild.alpha, EPS);
        assertEquals(0.0f, composeChild.translationX, EPS);

        // Apply multiple drag frames to qsRoot; its initial state (from before frame 1) is preserved.
        session.applyPageTransform(qsRoot, null, 756.0f, 1.0f, true, FAKE_ACCESS);
        session.applyPageTransform(qsRoot, null, 324.0f, 1.0f, true, FAKE_ACCESS);
        assertEquals(3, session.savedViewCount());

        // Compose slideFraction is pinned once per session.
        assertFalse(session.isComposeSlideFractionPinned());
        session.markComposeSlideFractionPinned(1.0f);
        assertTrue(session.isComposeSlideFractionPinned());
        assertEquals(1.0f, session.getSavedComposeSlideFraction(), EPS);

        // Ending the session restores the exact prior values on every touched view, including scale and Y.
        session.restoreAndEnd(FAKE_ACCESS);
        assertFalse(session.isActive());
        assertEquals(View.INVISIBLE, qsRoot.visibility);
        assertEquals(0.0f, qsRoot.alpha, EPS);
        assertEquals(0.0f, qsRoot.translationX, EPS);
        assertEquals(12.0f, qsRoot.translationY, EPS);
        assertEquals(0.94f, qsRoot.scaleX, EPS);
        assertEquals(0.94f, qsRoot.scaleY, EPS);

        assertEquals(View.INVISIBLE, ancestor.visibility);
        assertEquals(0.35f, ancestor.alpha, EPS);
        assertEquals(-24.0f, ancestor.translationX, EPS);
        assertEquals(8.0f, ancestor.translationY, EPS);
        assertEquals(0.98f, ancestor.scaleX, EPS);
        assertEquals(0.98f, ancestor.scaleY, EPS);

        assertEquals(View.INVISIBLE, composeChild.visibility);
        assertEquals(0.25f, composeChild.alpha, EPS);
        assertEquals(15.0f, composeChild.translationX, EPS);
        assertEquals(0, healthyAncestor.writeCount);
    }
}
