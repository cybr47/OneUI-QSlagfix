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
    private LinearLayout switchCard;
    private Switch verticalSwipeSwitch;
    private TextView noticeView;
    private boolean confirmedValue = ShadeSwitchPrefs.DEFAULT_VERTICAL_SWIPE;
    private boolean updatingUi;
    private int operationToken;
    private int accentTextColor;
    private int errorTextColor;

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
        setSwitchInteractive(false);
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
        setSwitchInteractive(false);
        PREFS_EXECUTOR.execute(() -> {
            try {
                SharedPreferences prefs = service.getRemotePreferences(ShadeSwitchPrefs.PREFS_GROUP);
                if (prefs == null) {
                    mainHandler.post(() -> onRemotePreferencesLoadFailed(
                            token, getString(R.string.lsposed_prefs_load_failed)));
                    return;
                }
                boolean value = ShadeSwitchPrefs.readVerticalSwipe(prefs);
                mainHandler.post(() -> onRemotePreferencesLoaded(token, service, prefs, value));
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
            int token, XposedService service, SharedPreferences prefs, boolean value) {
        if (isFinishing() || isDestroyed() || token != operationToken || boundService != service) {
            return;
        }
        remotePrefs = prefs;
        remotePrefsLoaded = true;
        confirmedValue = value;
        setSwitchCheckedSilently(confirmedValue);
        setSwitchInteractive(true);
        hideNotice();
    }

    private void onRemotePreferencesLoadFailed(int token, String message) {
        if (isFinishing() || isDestroyed() || token != operationToken) {
            return;
        }
        remotePrefs = null;
        remotePrefsLoaded = false;
        setSwitchCheckedSilently(confirmedValue);
        setSwitchInteractive(false);
        showNotice(message, true, true);
    }

    private void onXposedServiceDied() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        operationToken++;
        remotePrefs = null;
        remotePrefsLoaded = false;
        setSwitchCheckedSilently(confirmedValue);
        setSwitchInteractive(false);
        showNotice(getString(R.string.lsposed_service_disconnected), true, true);
    }

    private void onVerticalSwipeToggled(boolean desiredValue) {
        if (updatingUi) {
            return;
        }
        final SharedPreferences prefsSnapshot = remotePrefs;
        final XposedService serviceSnapshot = boundService;
        final boolean previousConfirmed = confirmedValue;
        if (!remotePrefsLoaded || prefsSnapshot == null || serviceSnapshot == null) {
            setSwitchCheckedSilently(previousConfirmed);
            setSwitchInteractive(false);
            showNotice(getString(R.string.lsposed_service_disconnected), true, true);
            return;
        }

        final int token = ++operationToken;
        // Disable interaction while the remote commit() executes off the main thread.
        setSwitchInteractive(false);
        PREFS_EXECUTOR.execute(() -> {
            ShadeSwitchPrefs.SaveResult result =
                    ShadeSwitchPrefs.commitVerticalSwipe(prefsSnapshot, desiredValue, previousConfirmed);
            mainHandler.post(() -> onVerticalSwipeCommitted(token, result));
        });
    }

    private void onVerticalSwipeCommitted(int token, ShadeSwitchPrefs.SaveResult result) {
        if (isFinishing() || isDestroyed() || token != operationToken) {
            return;
        }
        confirmedValue = result.currentValue;
        setSwitchCheckedSilently(confirmedValue);
        boolean serviceStillReady = boundService != null && remotePrefs != null && remotePrefsLoaded;
        setSwitchInteractive(serviceStillReady);
        if (!result.success) {
            showNotice(result.message, true, true);
            return;
        }
        showNotice(getString(R.string.restart_systemui_to_apply), false, true);
    }

    private void setSwitchInteractive(boolean enabled) {
        if (verticalSwipeSwitch != null) {
            verticalSwipeSwitch.setEnabled(enabled);
        }
        if (switchCard != null) {
            switchCard.setEnabled(enabled);
            switchCard.setClickable(enabled);
            switchCard.setAlpha(enabled ? 1.0f : 0.55f);
        }
    }

    private void setSwitchCheckedSilently(boolean checked) {
        if (verticalSwipeSwitch == null) {
            return;
        }
        updatingUi = true;
        try {
            verticalSwipeSwitch.setChecked(checked);
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

        switchCard = new LinearLayout(this);
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
        cardParams.topMargin = dp(20);
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
        switchTitle.setText(R.string.vertical_swipe_title);
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
        switchSummary.setText(R.string.vertical_swipe_summary);
        switchSummary.setTextColor(secondaryText);
        switchSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        switchSummary.setSingleLine(false);
        switchSummary.setIncludeFontPadding(true);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(6);
        textColumn.addView(switchSummary, summaryParams);

        verticalSwipeSwitch = new Switch(this);
        verticalSwipeSwitch.setEnabled(false);
        verticalSwipeSwitch.setContentDescription(getString(R.string.vertical_swipe_title));
        verticalSwipeSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> onVerticalSwipeToggled(isChecked));
        switchCard.addView(
                verticalSwipeSwitch,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        switchCard.setOnClickListener(v -> {
            if (verticalSwipeSwitch != null && verticalSwipeSwitch.isEnabled()) {
                verticalSwipeSwitch.toggle();
            }
        });
        switchCard.setEnabled(false);
        switchCard.setClickable(false);

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

    private int dp(int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value,
                        getResources().getDisplayMetrics()));
    }
}
