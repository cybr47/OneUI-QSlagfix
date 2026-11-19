package com.samsung.music.qs.lagfix;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** v2's two settings retain tgtr's keys but select the area-increase mode. */
public final class ShadeSwitchPrefsTest {
    private static final class Store {
        final Map<String, Boolean> values = new HashMap<>();
        final SharedPreferences.Editor editor;
        final SharedPreferences preferences;

        Store() {
            editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putBoolean":
                                values.put((String) args[0], (Boolean) args[1]);
                                return proxy;
                            case "commit":
                                return true;
                            case "apply":
                                return null;
                            case "clear":
                                values.clear();
                                return proxy;
                            case "remove":
                                values.remove((String) args[0]);
                                return proxy;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
            preferences = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getBoolean":
                                return values.getOrDefault((String) args[0], (Boolean) args[1]);
                            case "edit":
                                return editor;
                            case "contains":
                                return values.containsKey((String) args[0]);
                            case "getAll":
                                return new HashMap<>(values);
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    @Test public void v2DefaultsToStockHorizontalAndVisibleQuickQs() {
        Store store = new Store();
        assertFalse(ShadeSwitchPrefs.DEFAULT_VERTICAL_SWIPE);
        assertFalse(ShadeSwitchPrefs.readVerticalSwipe(store.preferences));
        assertFalse(store.preferences.getBoolean(ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW, false));
        assertEquals("vertical_swipe_to_switch_panels", ShadeSwitchPrefs.KEY_VERTICAL_SWIPE);
        assertEquals("hide_quick_qs_row", ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW);
        assertEquals("shade_switch", ShadeSwitchPrefs.PREFS_GROUP);
    }

    @Test public void verticalPreferenceAndTogetherPreferenceRemainIndependent() {
        Store store = new Store();
        assertTrue(ShadeSwitchPrefs.commitVerticalSwipe(
                store.preferences, true, false).success);
        assertTrue(ShadeSwitchPrefs.readVerticalSwipe(store.preferences));
        assertFalse(store.preferences.getBoolean(ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW, false));

        assertTrue(ShadeSwitchPrefs.commitBoolean(store.preferences,
                ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW, true, false).success);
        assertTrue(ShadeSwitchPrefs.readVerticalSwipe(store.preferences));
        assertTrue(store.preferences.getBoolean(ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW, false));

        assertTrue(ShadeSwitchPrefs.commitVerticalSwipe(
                store.preferences, false, true).success);
        assertFalse(ShadeSwitchPrefs.readVerticalSwipe(store.preferences));
        assertTrue(store.preferences.getBoolean(ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW, false));
    }

    @Test public void correctSummaryDescribesHeaderOnlyPulls() {
        assertTrue(ShadeSwitchPrefs.SWITCH_DESCRIPTION.contains("header"));
        assertFalse(ShadeSwitchPrefs.SWITCH_DESCRIPTION.contains("swipe down on the media player"));
    }
}
