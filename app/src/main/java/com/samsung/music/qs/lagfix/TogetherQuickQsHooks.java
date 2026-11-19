package com.samsung.music.qs.lagfix;

import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Uses the compact header's native zero-tile layout in Samsung's Together style. */
final class TogetherQuickQsHooks {
    private final SamsungMusicWavesHook module;
    private final Class<?> headerLayout;
    private final Field tileLayout;
    private final Field tileAdapter;
    private final Field columns;
    private final Field splitEnabled;
    private final Method setTiles;
    private final Method getTiles;
    private final Method setSplitEnabled;

    // The adapter also supplies the tile editor. Only the compact host's next synchronous
    // getTiles() read may see an empty collection.
    private final ThreadLocal<Object> pendingAdapter = new ThreadLocal<>();
    private final Set<Object> compactHosts = Collections.newSetFromMap(new WeakHashMap<>());

    private TogetherQuickQsHooks(SamsungMusicWavesHook module, ClassLoader loader)
            throws ReflectiveOperationException {
        this.module = module;
        Class<?> host = loader.loadClass("com.android.systemui.qs.QSPanelHost");
        Class<?> adapter = loader.loadClass("com.android.systemui.qs.QuickQSHostAdapter");
        Class<?> splitHelper = loader.loadClass("com.android.systemui.shade.SecPanelSplitHelper");
        Class<?> layout = loader.loadClass("com.android.systemui.qs.TileLayout");
        headerLayout = loader.loadClass("com.android.systemui.qs.SecHeaderTileLayout");
        tileLayout = field(host, "mTileLayout");
        tileAdapter = field(host, "mQsHost");
        columns = field(layout, "mColumns");
        splitEnabled = field(splitHelper, "isEnabled");
        setTiles = host.getDeclaredMethod("setTiles", Boolean.class);
        setTiles.setAccessible(true);
        getTiles = adapter.getDeclaredMethod("getTiles");
        setSplitEnabled = splitHelper.getDeclaredMethod("setEnabled$1", boolean.class);
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            TogetherQuickQsHooks hooks = new TogetherQuickQsHooks(module, loader);
            // getTiles() is small enough to be inlined at the only call site we filter.
            if (!module.deoptimize(hooks.setTiles)) {
                throw new IllegalStateException("Unable to deoptimize the compact tile binding");
            }
            handles.add(module.hook(hooks.getTiles).setId("together-compact-tile-read")
                    .intercept(hooks::readTiles));
            handles.add(module.hook(hooks.setSplitEnabled).setId("together-panel-style")
                    .intercept(hooks::changePanelStyle));
            handles.add(module.hook(hooks.setTiles).setId("together-compact-tile-bind")
                    .intercept(hooks::bindTiles));
            module.note("Together style compact tile row hook installed");
        } catch (Throwable error) {
            for (int i = handles.size() - 1; i >= 0; i--) {
                handles.get(i).unhook();
            }
            module.warn("Unable to install the Together style compact tile row hook", error);
        }
    }

    private Object bindTiles(XposedInterface.Chain chain) throws Throwable {
        Object host = chain.getThisObject();
        ViewGroup row = (ViewGroup) tileLayout.get(host);
        Object adapter = tileAdapter.get(host);
        boolean compact = headerLayout.isInstance(row) && getTiles.getDeclaringClass().isInstance(adapter);
        if (compact) {
            synchronized (compactHosts) {
                compactHosts.add(host);
            }
        }
        boolean hide = compact && !splitEnabled.getBoolean(null);
        Object previous = pendingAdapter.get();
        if (!hide && previous == null) {
            return chain.proceed();
        }
        if (hide) {
            pendingAdapter.set(adapter);
        } else {
            pendingAdapter.remove();
        }
        try {
            Object result = chain.proceed();
            // The parent measures before the child's onLayout normally clears the old column
            // count. Drop that stale count on this pass so it cannot reserve the old top margin.
            if (hide && row.getChildCount() == 0 && columns.getInt(row) != 0) {
                columns.setInt(row, 0);
                row.requestLayout();
            }
            return result;
        } finally {
            if (previous == null) {
                pendingAdapter.remove();
            } else {
                pendingAdapter.set(previous);
            }
        }
    }

    private Object readTiles(XposedInterface.Chain chain) throws Throwable {
        Object adapter = pendingAdapter.get();
        if (adapter != null && adapter == chain.getThisObject()) {
            pendingAdapter.remove();
            return Collections.emptyList();
        }
        return chain.proceed();
    }

    private Object changePanelStyle(XposedInterface.Chain chain) throws Throwable {
        boolean wasSplit = splitEnabled.getBoolean(null);
        Object result = chain.proceed();
        if (wasSplit != splitEnabled.getBoolean(null)) {
            List<Object> hosts;
            synchronized (compactHosts) {
                hosts = new ArrayList<>(compactHosts);
            }
            for (Object host : hosts) {
                try {
                    ViewGroup row = (ViewGroup) tileLayout.get(host);
                    if (row != null && row.isAttachedToWindow()) {
                        module.getInvoker(setTiles).setType(XposedInterface.Invoker.Type.Chain.FULL)
                                .invoke(host, Boolean.FALSE);
                    }
                } catch (ReflectiveOperationException error) {
                    module.warn("Unable to refresh the compact tile row after a panel style change", error);
                }
            }
        }
        return result;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
