package com.samsung.music.qs.lagfix;

import android.app.ActionBar;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Module settings screen accessible from {@code LSPosed -> Modules -> ShadeSwitch} via
 * {@code de.robv.android.xposed.category.MODULE_SETTINGS}.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Uses an activity-scoped no-action-bar theme ({@code Theme.DeviceDefault.NoActionBar}) and
 *       requests {@link Window#FEATURE_NO_TITLE} so only the single in-content {@code ShadeSwitch}
 *       title is rendered above the switch card.
 *   <li>Applies {@link WindowInsets.Type#systemBars()} and
 *       {@link WindowInsets.Type#displayCutout()} insets once on the content container without
 *       accumulating padding across repeated inset passes, and keeps the full switch row label and
 *       summary visible at larger font sizes.
 *   <li>The switch and its clickable row start <b>disabled</b> and are enabled only after
 *       {@link XposedServiceHelper} binds to LSPosed's {@link XposedService} and the remote
 *       preference has loaded off the main thread.
 *   <li>No local {@link SharedPreferences} cache is used, so a stale local value can never
 *       overwrite a confirmed remote choice or falsely report a save before the service connects.
 *   <li>Toggling the switch saves through LSPosed Remote Preferences and checks the result of
 *       {@link SharedPreferences.Editor#commit()} off the main thread on {@link #PREFS_EXECUTOR},
 *       showing {@code "Restart SystemUI to apply."} only after a confirmed write.
 *   <li>If the write fails or the service disconnects, the UI reverts to the last confirmed mode,
 *       displays the failure message, and allows the user to retry.
 * </ul>
 */
public final class ShadeSwitchSettingsActivity extends Activity {
    private static final ExecutorService PREFS_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ShadeSwitch-RemotePrefs");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile XposedService boundService;
    private static volatile ShadeSwitchSettingsActivity activeActivity;
    private static boolean listenerRegistered;

    private static final XposedServiceHelper.OnServiceListener SERVICE_LISTENER =
            new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    boundService = service;
                    ShadeSwitchSettingsActivity activity = activeActivity;
                    if (activity != null) {
                        activity.mainHandler.post(() -> activity.loadRemotePreferencesAsync(service));
                    }
                }

                @Override
                public void onServiceDied(XposedService service) {
                    if (boundService == service) {
                        boundService = null;
                    }
                    ShadeSwitchSettingsActivity activity = activeActivity;
                    if (activity != null) {
                        activity.mainHandler.post(activity::onXposedServiceDied);
                    }
                }
            };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences remotePrefs;
    private boolean remotePrefsLoaded;
    private ToggleRow verticalSwipe;
    private ToggleRow hideQuickQs;
    private TextView noticeView;
    private boolean updatingUi;
    private int operationToken;
    private int accentTextColor;
    private int errorTextColor;

    private static final class ToggleRow {
        final String key;
        final LinearLayout card;
        final Switch toggle;
        boolean confirmed;

        ToggleRow(String key, LinearLayout card, Switch toggle) {
            this.key = key;
            this.card = card;
            this.toggle = toggle;
        }

        void setInteractive(boolean enabled) {
            toggle.setEnabled(enabled);
            card.setEnabled(enabled);
            card.setClickable(enabled);
            card.setAlpha(enabled ? 1.0f : 0.55f);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        activeActivity = this;

        buildContentView();
        setSwitchesInteractive(false);
        showNotice(getString(R.string.lsposed_service_waiting), false, false);

        ensureServiceListenerRegistered();
        XposedService existing = boundService;
        if (existing != null) {
            loadRemotePreferencesAsync(existing);
        }
    }

    @Override
    protected void onDestroy() {
        operationToken++;
        if (activeActivity == this) {
            activeActivity = null;
        }
        super.onDestroy();
    }

    /**
     * Computes the content container's Left/Top/Right/Bottom padding by adding the non-negative
     * {@code systemBars() | displayCutout()} insets to the base content padding exactly once.
     */
    static int[] computeContentPadding(
            int baseLeft,
            int baseTop,
            int baseRight,
            int baseBottom,
            int insetLeft,
            int insetTop,
            int insetRight,
            int insetBottom) {
        return new int[]{
            baseLeft + Math.max(0, insetLeft),
            baseTop + Math.max(0, insetTop),
            baseRight + Math.max(0, insetRight),
            baseBottom + Math.max(0, insetBottom)
        };
    }

    private static synchronized void ensureServiceListenerRegistered() {
        if (!listenerRegistered) {
            listenerRegistered = true;
            XposedServiceHelper.registerListener(SERVICE_LISTENER);
        }
    }

    private void loadRemotePreferencesAsync(XposedService service) {
        if (isFinishing() || isDestroyed() || service == null) {
            return;
        }
        final int token = ++operationToken;
        setSwitchesInteractive(false);
        PREFS_EXECUTOR.execute(() -> {
            try {
                SharedPreferences prefs = service.getRemotePreferences(ShadeSwitchPrefs.PREFS_GROUP);
                if (prefs == null) {
                    mainHandler.post(() -> onRemotePreferencesLoadFailed(
                            token, getString(R.string.lsposed_prefs_load_failed)));
                    return;
                }
                boolean vertical = ShadeSwitchPrefs.readVerticalSwipe(prefs);
                boolean hideRow = prefs.getBoolean(ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW, false);
                mainHandler.post(() -> onRemotePreferencesLoaded(token, service, prefs, vertical, hideRow));
            } catch (Throwable error) {
                String detail = error.getMessage();
                String message = (detail != null && !detail.isEmpty())
                        ? getString(R.string.lsposed_prefs_load_failed) + " (" + detail + ")"
                        : getString(R.string.lsposed_prefs_load_failed);
                mainHandler.post(() -> onRemotePreferencesLoadFailed(token, message));
            }
        });
    }

    private void onRemotePreferencesLoaded(
            int token, XposedService service, SharedPreferences prefs, boolean vertical, boolean hideRow) {
        if (isFinishing() || isDestroyed() || token != operationToken || boundService != service) {
            return;
        }
        remotePrefs = prefs;
        remotePrefsLoaded = true;
        verticalSwipe.confirmed = vertical;
        hideQuickQs.confirmed = hideRow;
        showConfirmedValues();
        setSwitchesInteractive(true);
        hideNotice();
    }

    private void onRemotePreferencesLoadFailed(int token, String message) {
        if (isFinishing() || isDestroyed() || token != operationToken) {
            return;
        }
        remotePrefs = null;
        remotePrefsLoaded = false;
        showConfirmedValues();
        setSwitchesInteractive(false);
        showNotice(message, true, true);
    }

    private void onXposedServiceDied() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        operationToken++;
        remotePrefs = null;
        remotePrefsLoaded = false;
        showConfirmedValues();
        setSwitchesInteractive(false);
        showNotice(getString(R.string.lsposed_service_disconnected), true, true);
    }

    private void onToggled(ToggleRow row, boolean desiredValue) {
        if (updatingUi) {
            return;
        }
        final SharedPreferences prefsSnapshot = remotePrefs;
        final XposedService serviceSnapshot = boundService;
        final boolean previousConfirmed = row.confirmed;
        if (!remotePrefsLoaded || prefsSnapshot == null || serviceSnapshot == null) {
            showConfirmedValues();
            setSwitchesInteractive(false);
            showNotice(getString(R.string.lsposed_service_disconnected), true, true);
            return;
        }

        final int token = ++operationToken;
        // Disable interaction while the remote commit() executes off the main thread.
        setSwitchesInteractive(false);
        PREFS_EXECUTOR.execute(() -> {
            ShadeSwitchPrefs.SaveResult result =
                    ShadeSwitchPrefs.commitBoolean(prefsSnapshot, row.key, desiredValue, previousConfirmed);
            mainHandler.post(() -> onCommitted(token, row, result));
        });
    }

    private void onCommitted(int token, ToggleRow row, ShadeSwitchPrefs.SaveResult result) {
        if (isFinishing() || isDestroyed() || token != operationToken) {
            return;
        }
        row.confirmed = result.currentValue;
        showConfirmedValues();
        boolean serviceStillReady = boundService != null && remotePrefs != null && remotePrefsLoaded;
        setSwitchesInteractive(serviceStillReady);
        if (!result.success) {
            showNotice(result.message, true, true);
            return;
        }
        showNotice(getString(R.string.restart_systemui_to_apply), false, true);
    }

    private void setSwitchesInteractive(boolean enabled) {
        verticalSwipe.setInteractive(enabled);
        hideQuickQs.setInteractive(enabled);
    }

    private void showConfirmedValues() {
        updatingUi = true;
        try {
            verticalSwipe.toggle.setChecked(verticalSwipe.confirmed);
            hideQuickQs.toggle.setChecked(hideQuickQs.confirmed);
        } finally {
            updatingUi = false;
        }
    }

    private void showNotice(String message, boolean isError, boolean showToast) {
        if (noticeView != null) {
            noticeView.setText(message);
            noticeView.setTextColor(isError ? errorTextColor : accentTextColor);
            noticeView.setVisibility(View.VISIBLE);
        }
        if (showToast) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideNotice() {
        if (noticeView != null) {
            noticeView.setVisibility(View.GONE);
        }
    }

    private void buildContentView() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int pageBg = night ? 0xFF0E1015 : 0xFFF4F6FA;
        int cardBg = night ? 0xFF1A1D24 : 0xFFFFFFFF;
        int cardStroke = night ? 0xFF2B303C : 0xFFE2E6EF;
        int primaryText = night ? 0xFFF5F7FA : 0xFF14171F;
        int secondaryText = night ? 0xFFA9B1C2 : 0xFF5A6275;
        accentTextColor = night ? 0xFF8AB4F8 : 0xFF1A73E8;
        errorTextColor = night ? 0xFFF28B82 : 0xFFD93025;

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(pageBg);
            window.setNavigationBarColor(pageBg);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(pageBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int basePadH = dp(20);
        final int basePadV = dp(24);
        root.setPadding(basePadH, basePadV, basePadH, basePadV);
        scrollView.addView(
                root,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT));

        // Apply system-bar and display-cutout insets once to root's base padding so the single
        // in-content title and switch card always sit cleanly below the status bar / cutout.
        scrollView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int[] pad = computeContentPadding(
                    basePadH,
                    basePadV,
                    basePadH,
                    basePadV,
                    insets.left,
                    insets.top,
                    insets.right,
                    insets.bottom);
            root.setPadding(pad[0], pad[1], pad[2], pad[3]);
            return WindowInsets.CONSUMED;
        });

        TextView headerTitle = new TextView(this);
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextColor(primaryText);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        headerTitle.getPaint().setFakeBoldText(true);
        headerTitle.setSingleLine(false);
        headerTitle.setIncludeFontPadding(true);
        root.addView(
                headerTitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        verticalSwipe = addToggleRow(root, ShadeSwitchPrefs.KEY_VERTICAL_SWIPE,
                R.string.vertical_swipe_title, R.string.vertical_swipe_summary,
                cardBg, cardStroke, primaryText, secondaryText, 20);

        TextView togetherHeading = new TextView(this);
        togetherHeading.setText(R.string.together_style);
        togetherHeading.setTextColor(secondaryText);
        togetherHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        togetherHeading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(24);
        headingParams.setMarginStart(dp(4));
        root.addView(togetherHeading, headingParams);

        hideQuickQs = addToggleRow(root, ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW,
                R.string.hide_quick_qs_title, R.string.hide_quick_qs_summary,
                cardBg, cardStroke, primaryText, secondaryText, 8);

        noticeView = new TextView(this);
        noticeView.setText(R.string.lsposed_service_waiting);
        noticeView.setTextColor(accentTextColor);
        noticeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        noticeView.setSingleLine(false);
        noticeView.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        noticeParams.topMargin = dp(14);
        noticeParams.setMarginStart(dp(4));
        root.addView(noticeView, noticeParams);

        setContentView(scrollView);
        scrollView.requestApplyInsets();
    }

    private ToggleRow addToggleRow(LinearLayout root, String key, int titleRes, int summaryRes,
            int cardBg, int cardStroke, int primaryText, int secondaryText, int topMarginDp) {
        LinearLayout switchCard = new LinearLayout(this);
        switchCard.setOrientation(LinearLayout.HORIZONTAL);
        switchCard.setGravity(Gravity.CENTER_VERTICAL);
        int cardPad = dp(18);
        switchCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        GradientDrawable cardDrawable = new GradientDrawable();
        cardDrawable.setColor(cardBg);
        cardDrawable.setCornerRadius(dp(18));
        cardDrawable.setStroke(dp(1), cardStroke);
        switchCard.setBackground(cardDrawable);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(topMarginDp);
        root.addView(switchCard, cardParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f);
        textColParams.setMarginEnd(dp(16));
        switchCard.addView(textColumn, textColParams);

        TextView switchTitle = new TextView(this);
        switchTitle.setText(titleRes);
        switchTitle.setTextColor(primaryText);
        switchTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        switchTitle.getPaint().setFakeBoldText(true);
        switchTitle.setSingleLine(false);
        switchTitle.setIncludeFontPadding(true);
        textColumn.addView(
                switchTitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView switchSummary = new TextView(this);
        switchSummary.setText(summaryRes);
        switchSummary.setTextColor(secondaryText);
        switchSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        switchSummary.setSingleLine(false);
        switchSummary.setIncludeFontPadding(true);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(6);
        textColumn.addView(switchSummary, summaryParams);

        Switch toggle = new Switch(this);
        ToggleRow row = new ToggleRow(key, switchCard, toggle);
        toggle.setContentDescription(getString(titleRes));
        toggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> onToggled(row, isChecked));
        switchCard.addView(
                toggle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        switchCard.setOnClickListener(v -> toggle.toggle());
        row.setInteractive(false);
        return row;
    }

    private int dp(int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value,
                        getResources().getDisplayMetrics()));
    }
}
