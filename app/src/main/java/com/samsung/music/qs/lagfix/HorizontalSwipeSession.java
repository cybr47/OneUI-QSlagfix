package com.samsung.music.qs.lagfix;

import android.view.View;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Tracks an active horizontal panel-transition gesture on a {@code PanelTransitionAnimator}
 * instance, caching resolved per-instance references and recording the exact prior properties of
 * every view (including ancestor containers and Compose children) before any horizontal swipe
 * mutation occurs.
 *
 * <p>When no {@code HorizontalSwipeSession} is active, ordinary vertical shade animations,
 * lockscreen transitions, and non-horizontal state changes run completely untouched. When an active
 * horizontal swipe ends (via completion, cancellation/reversal, or panel close), all modified views
 * are restored to their actual prior values before native settlement runs.
 */
final class HorizontalSwipeSession {
    /**
     * Abstraction over a view's transform and visibility properties so the exact save-before-mutate
     * and restore lifecycle can be verified in pure JUnit tests as well as on Android {@link View}s.
     */
    interface PropertyAccess<T> {
        int getVisibility(T target);
        void setVisibility(T target, Object animWrapper, int visibility);

        float getAlpha(T target);
        void setAlpha(T target, Object animWrapper, float alpha);

        float getTranslationX(T target);
        void setTranslationX(T target, Object animWrapper, float translationX);

        float getTranslationY(T target);
        void setTranslationY(T target, Object animWrapper, float translationY);

        float getScaleX(T target);
        void setScaleX(T target, Object animWrapper, float scaleX);

        float getScaleY(T target);
        void setScaleY(T target, Object animWrapper, float scaleY);
    }

    static final class SavedPropertyState<T> {
        final T target;
        final Object animWrapper;
        final int visibility;
        final float alpha;
        final float translationX;
        final float translationY;
        final float scaleX;
        final float scaleY;

        SavedPropertyState(T target, Object animWrapper, PropertyAccess<T> access) {
            this.target = target;
            this.animWrapper = animWrapper;
            this.visibility = access.getVisibility(target);
            this.alpha = access.getAlpha(target);
            this.translationX = access.getTranslationX(target);
            this.translationY = access.getTranslationY(target);
            this.scaleX = access.getScaleX(target);
            this.scaleY = access.getScaleY(target);
        }

        void restore(PropertyAccess<T> access) {
            access.setVisibility(target, animWrapper, visibility);
            access.setAlpha(target, animWrapper, alpha);
            access.setTranslationX(target, animWrapper, translationX);
            access.setTranslationY(target, animWrapper, translationY);
            access.setScaleX(target, animWrapper, scaleX);
            access.setScaleY(target, animWrapper, scaleY);
        }
    }

    private final IdentityHashMap<Object, SavedPropertyState<?>> savedByTarget = new IdentityHashMap<>();
    private final List<SavedPropertyState<?>> savedInOrder = new ArrayList<>();

    private boolean active;
    private boolean composeSlideFractionPinned;
    private Float savedComposeSlideFraction;

    // Cached per-animator instance references resolved once when the horizontal swipe begins.
    Object splitHelper;
    Object composeAdapter;
    Object slideFractionFlow;
    View panelRootView;
    Object qsAnimView;
    Object shadeAnimView;
    Object buttonAnimView;
    Object clockDateAnimView;
    Object headerAnimView;
    Object plmnAnimView;
    View qsRootView;
    View shadeRootView;
    View buttonView;
    View clockDateView;
    View headerView;
    View plmnView;
    View qsButtonsContainer;
    boolean reversed;
    boolean drawablesPrepared;

    void begin() {
        active = true;
        composeSlideFractionPinned = false;
        savedComposeSlideFraction = null;
        drawablesPrepared = false;
        savedByTarget.clear();
        savedInOrder.clear();
    }

    boolean isActive() {
        return active;
    }

    boolean isComposeSlideFractionPinned() {
        return composeSlideFractionPinned;
    }

    void markComposeSlideFractionPinned(Float priorSlideFraction) {
        if (!composeSlideFractionPinned) {
            savedComposeSlideFraction = priorSlideFraction;
            composeSlideFractionPinned = true;
        }
    }

    Float getSavedComposeSlideFraction() {
        return savedComposeSlideFraction;
    }

    int savedViewCount() {
        return savedInOrder.size();
    }

    <T> void saveIfAbsent(T target, Object animWrapper, PropertyAccess<T> access) {
        if (target == null || !active || savedByTarget.containsKey(target)) {
            return;
        }
        SavedPropertyState<T> snapshot = new SavedPropertyState<>(target, animWrapper, access);
        savedByTarget.put(target, snapshot);
        savedInOrder.add(snapshot);
    }

    /**
     * Ensures an ancestor or Compose child target is drawable ({@code visibility == View.VISIBLE},
     * {@code alpha == 1.0f}, {@code translationX == 0.0f}), saving its prior state first ONLY if a
     * property actually needs to be modified.
     */
    <T> void ensureDrawable(T target, PropertyAccess<T> access) {
        if (target == null || !active) {
            return;
        }
        int vis = access.getVisibility(target);
        float alpha = access.getAlpha(target);
        float tx = access.getTranslationX(target);
        if (vis == View.VISIBLE && alpha == 1.0f && tx == 0.0f) {
            return;
        }
        saveIfAbsent(target, null, access);
        if (vis != View.VISIBLE) {
            access.setVisibility(target, null, View.VISIBLE);
        }
        if (alpha != 1.0f) {
            access.setAlpha(target, null, 1.0f);
        }
        if (tx != 0.0f) {
            access.setTranslationX(target, null, 0.0f);
        }
    }

    /**
     * Saves the prior state of a panel view (if not already saved during this session) and applies
     * the horizontal page translation and opacity.
     */
    <T> void applyPageTransform(
            T target,
            Object animWrapper,
            float translationX,
            float alpha,
            boolean ensureVisible,
            PropertyAccess<T> access) {
        if (target == null || !active) {
            return;
        }
        saveIfAbsent(target, animWrapper, access);
        if (ensureVisible && access.getVisibility(target) != View.VISIBLE) {
            access.setVisibility(target, animWrapper, View.VISIBLE);
        }
        if (access.getTranslationX(target) != translationX) {
            access.setTranslationX(target, animWrapper, translationX);
        }
        if (access.getAlpha(target) != alpha) {
            access.setAlpha(target, animWrapper, alpha);
        }
    }

    /**
     * Keeps a shared chrome view stationary at {@code translationX = 0.0f}, saving its prior state
     * first only if {@code translationX} was displaced.
     */
    <T> void keepStationaryX(T target, Object animWrapper, PropertyAccess<T> access) {
        if (target == null || !active) {
            return;
        }
        if (access.getTranslationX(target) != 0.0f) {
            saveIfAbsent(target, animWrapper, access);
            access.setTranslationX(target, animWrapper, 0.0f);
        }
    }

    /**
     * Restores all modified views (in reverse order of modification) to their exact saved prior
     * values and marks this horizontal swipe session inactive.
     */
    @SuppressWarnings("unchecked")
    <T> void restoreAndEnd(PropertyAccess<T> access) {
        if (!active) {
            return;
        }
        active = false;
        for (int i = savedInOrder.size() - 1; i >= 0; i--) {
            SavedPropertyState<T> state = (SavedPropertyState<T>) savedInOrder.get(i);
            state.restore(access);
        }
        savedInOrder.clear();
        savedByTarget.clear();
        composeSlideFractionPinned = false;
        drawablesPrepared = false;
    }
}
