package com.samsung.music.qs.lagfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.view.View;
import com.samsung.music.qs.lagfix.HorizontalTransitionMath.FrameTransform;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util. Set;
import org.junit.Test;

/**
 * Unit tests verifying the GlassUI-style horizontal transition math, eased {@code 50%} fade curve,
 * {@link HorizontalSwipeSession} lifecycle, nested notification blur-alpha protection, and
 * {@link ShadeSwitchPrefs} Remote Preferences commit verification on the {@code cust} branch.
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

    private static final class FakeRemotePrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();
        boolean commitSucceeds = true;
        boolean simulateLibxposedInMemoryMutateBeforeCommitFailure = true;
        RuntimeException commitException;

        @Override
        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(map);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = map.get(key);
            return v instanceof String s ? s : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = map.get(key);
            return v instanceof Set<?> ? (Set<String>) v : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = map.get(key);
            return v instanceof Integer i ? i : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = map.get(key);
            return v instanceof Long l ? l : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = map.get(key);
            return v instanceof Float f ? f : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = map.get(key);
            return v instanceof Boolean b ? b : defValue;
        }

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> staged = new HashMap<>();

                @Override
                public Editor putString(String key, String value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> values) {
                    staged.put(key, values);
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(String key, float value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    staged.remove(key);
                    return this;
                }

                @Override
                public Editor clear() {
                    staged.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    if (commitException != null) {
                        throw commitException;
                    }
                    if (commitSucceeds || simulateLibxposedInMemoryMutateBeforeCommitFailure) {
                        map.putAll(staged);
                    }
                    return commitSucceeds;
                }

                @Override
                public void apply() {
                    commit();
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
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
    public void fadeCutoffIs050WithEasedOpacityCurveInBothDirections() {
        assertEquals(0.50f, HorizontalTransitionMath.FADE_CUTOFF, EPS);

        assertEquals(1.0f, HorizontalTransitionMath.outgoingAlpha(0.0f), EPS);
        assertTrue(HorizontalTransitionMath.outgoingAlpha(0.01f) < 1.0f);
        assertEquals(0.65625f, HorizontalTransitionMath.outgoingAlpha(0.25f), EPS);
        assertEquals(0.17568f, HorizontalTransitionMath.outgoingAlpha(0.40f), EPS);
        assertEquals(0.0f, HorizontalTransitionMath.outgoingAlpha(0.50f), EPS);
        assertEquals(0.0f, HorizontalTransitionMath.outgoingAlpha(0.75f), EPS);
        assertEquals(0.0f, HorizontalTransitionMath.outgoingAlpha(1.0f), EPS);

        for (float p : new float[]{0.0f, 0.01f, 0.25f, 0.40f, 0.50f, 0.80f, 1.0f}) {
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

        FrameTransform at25 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.25f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(-0.25f * WIDTH, at25.notificationsTranslationX, EPS);
        assertEquals(+0.75f * WIDTH, at25.qsTranslationX, EPS);
        assertEquals(0.65625f, at25.notificationsAlpha, EPS);
        assertEquals(1.0f, at25.qsAlpha, EPS);

        FrameTransform at40 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.40f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(-0.40f * WIDTH, at40.notificationsTranslationX, EPS);
        assertEquals(+0.60f * WIDTH, at40.qsTranslationX, EPS);
        assertEquals(0.17568f, at40.notificationsAlpha, EPS);
        assertEquals(1.0f, at40.qsAlpha, EPS);

        FrameTransform atCutoff = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.50f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(-0.50f * WIDTH, atCutoff.notificationsTranslationX, EPS);
        assertEquals(+0.50f * WIDTH, atCutoff.qsTranslationX, EPS);
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
        FrameTransform at25 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.25f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                false);
        assertTrue(at25.activeHorizontal);
        assertFalse(at25.movingToQs);
        assertEquals(+0.25f * WIDTH, at25.qsTranslationX, EPS);
        assertEquals(-0.75f * WIDTH, at25.notificationsTranslationX, EPS);
        assertEquals(0.65625f, at25.qsAlpha, EPS);
        assertEquals(1.0f, at25.notificationsAlpha, EPS);

        FrameTransform at40 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.40f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                false);
        assertEquals(+0.40f * WIDTH, at40.qsTranslationX, EPS);
        assertEquals(-0.60f * WIDTH, at40.notificationsTranslationX, EPS);
        assertEquals(0.17568f, at40.qsAlpha, EPS);
        assertEquals(1.0f, at40.notificationsAlpha, EPS);

        FrameTransform atCutoff = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.50f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                false);
        assertEquals(+0.50f * WIDTH, atCutoff.qsTranslationX, EPS);
        assertEquals(-0.50f * WIDTH, atCutoff.notificationsTranslationX, EPS);
        assertEquals(0.0f, atCutoff.qsAlpha, EPS);
        assertEquals(1.0f, atCutoff.notificationsAlpha, EPS);
    }

    @Test
    public void reversedPanelOrderMirrorsTranslationDirectionsAndKeepsEasedFade() {
        FrameTransform toQsAt25 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.25f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                true);
        assertTrue(toQsAt25.movingToQs);
        assertEquals(+0.25f * WIDTH, toQsAt25.notificationsTranslationX, EPS);
        assertEquals(-0.75f * WIDTH, toQsAt25.qsTranslationX, EPS);
        assertEquals(0.65625f, toQsAt25.notificationsAlpha, EPS);
        assertEquals(1.0f, toQsAt25.qsAlpha, EPS);

        FrameTransform toShadeAt40 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.40f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                true);
        assertFalse(toShadeAt40.movingToQs);
        assertEquals(-0.40f * WIDTH, toShadeAt40.qsTranslationX, EPS);
        assertEquals(+0.60f * WIDTH, toShadeAt40.notificationsTranslationX, EPS);
        assertEquals(0.17568f, toShadeAt40.qsAlpha, EPS);
        assertEquals(1.0f, toShadeAt40.notificationsAlpha, EPS);
    }

    @Test
    public void midDragHoldAndReversalHoldsAndRestoresOutgoingOpacityAlongSameCurve() {
        // Hold at 40% drag in Notifications -> QS direction
        FrameTransform drag40 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.40f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        FrameTransform hold40 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.40f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(drag40.qsTranslationX, hold40.qsTranslationX, EPS);
        assertEquals(drag40.notificationsTranslationX, hold40.notificationsTranslationX, EPS);
        assertEquals(0.17568f, hold40.notificationsAlpha, EPS);
        assertEquals(1.0f, hold40.qsAlpha, EPS);

        // Drag past 50% (to 60%), hold, then reverse back through 40% and 25%
        FrameTransform drag60 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.60f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(0.0f, drag60.notificationsAlpha, EPS);

        FrameTransform reverse40 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.40f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(WIDTH, reverse40.qsTranslationX - reverse40.notificationsTranslationX, EPS);
        assertEquals(-0.40f * WIDTH, reverse40.notificationsTranslationX, EPS);
        assertEquals(+0.60f * WIDTH, reverse40.qsTranslationX, EPS);
        assertEquals(0.17568f, reverse40.notificationsAlpha, EPS);
        assertEquals(1.0f, reverse40.qsAlpha, EPS);

        FrameTransform reverse25 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.25f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_LEFT,
                HorizontalTransitionMath.QS_STATE,
                WIDTH,
                false);
        assertEquals(0.65625f, reverse25.notificationsAlpha, EPS);
        assertEquals(-0.25f * WIDTH, reverse25.notificationsTranslationX, EPS);
        assertEquals(+0.75f * WIDTH, reverse25.qsTranslationX, EPS);

        // Also verify hold and reversal in the opposite direction (QS -> Notifications)
        FrameTransform qsReverse25 = FrameTransform.forSlide(
                HorizontalTransitionMath.TRANSITION_STATE,
                0.25f,
                0.0f,
                HorizontalTransitionMath.DIRECTION_RIGHT,
                HorizontalTransitionMath.SHADE_STATE,
                WIDTH,
                false);
        assertEquals(0.65625f, qsReverse25.qsAlpha, EPS);
        assertEquals(1.0f, qsReverse25.notificationsAlpha, EPS);
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

        float[] flingFrames = new float[]{0.15f, 0.40f, 0.75f, 1.0f};
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
    public void nestedNotificationBlurAlphaStaysAt1DuringHorizontalSwipeAndRestoresWhenInactive() {
        assertEquals(
                1.0f,
                HorizontalTransitionMath.effectiveNestedNotificationBlurAlpha(true, 0.65625f),
                EPS);
        assertEquals(
                1.0f,
                HorizontalTransitionMath.effectiveNestedNotificationBlurAlpha(true, 0.17568f),
                EPS);
        assertEquals(
                1.0f,
                HorizontalTransitionMath.effectiveNestedNotificationBlurAlpha(true, 0.0f),
                EPS);
        assertEquals(
                0.0f,
                HorizontalTransitionMath.effectiveNestedNotificationBlurAlpha(false, 0.0f),
                EPS);
        assertEquals(
                1.0f,
                HorizontalTransitionMath.effectiveNestedNotificationBlurAlpha(false, 1.0f),
                EPS);
    }

    @Test
    public void shadeSwitchRemotePreferencesCommitVerifiesWriteAndRevertsOnFailure() {
        assertFalse(ShadeSwitchPrefs.DEFAULT_VERTICAL_SWIPE);
        assertEquals("Vertical swipe to switch panels", ShadeSwitchPrefs.SWITCH_TITLE);
        assertEquals(
                "Swipe down from the open panel\u2019s header to switch between Notifications and Quick Settings.",
                ShadeSwitchPrefs.SWITCH_DESCRIPTION);
        assertEquals("Restart SystemUI to apply.", ShadeSwitchPrefs.RESTART_SYSTEMUI_NOTICE);

        // 1. Disconnected service (null RemotePreferences) rejects save and keeps previous value.
        ShadeSwitchPrefs.SaveResult disconnected =
                ShadeSwitchPrefs.commitVerticalSwipe(null, true, false);
        assertFalse(disconnected.success);
        assertFalse(disconnected.currentValue);
        assertEquals(ShadeSwitchPrefs.SERVICE_DISCONNECTED_NOTICE, disconnected.message);

        // 2. Connected service with successful commit saves true and shows restart notice.
        FakeRemotePrefs remotePrefs = new FakeRemotePrefs();
        assertFalse(ShadeSwitchPrefs.readVerticalSwipe(remotePrefs));
        ShadeSwitchPrefs.SaveResult savedTrue =
                ShadeSwitchPrefs.commitVerticalSwipe(remotePrefs, true, false);
        assertTrue(savedTrue.success);
        assertTrue(savedTrue.currentValue);
        assertTrue(ShadeSwitchPrefs.readVerticalSwipe(remotePrefs));
        assertEquals(ShadeSwitchPrefs.RESTART_SYSTEMUI_NOTICE, savedTrue.message);

        // 3. Failed IPC commit (commit() returns false after mutating in-memory map) rolls back
        //    to previousValue and reports failure instead of "Restart SystemUI to apply."
        remotePrefs.commitSucceeds = false;
        ShadeSwitchPrefs.SaveResult failedSave =
                ShadeSwitchPrefs.commitVerticalSwipe(remotePrefs, false, true);
        assertFalse(failedSave.success);
        assertTrue(failedSave.currentValue);
        assertEquals(ShadeSwitchPrefs.SAVE_FAILED_NOTICE, failedSave.message);

        // 4. Exception thrown during commit preserves previousValue and surfaces the error message.
        remotePrefs.commitException = new RuntimeException("Binder transaction failed");
        ShadeSwitchPrefs.SaveResult exceptionSave =
                ShadeSwitchPrefs.commitVerticalSwipe(remotePrefs, false, true);
        assertFalse(exceptionSave.success);
        assertTrue(exceptionSave.currentValue);
        assertTrue(exceptionSave.message.contains("Binder transaction failed"));
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
