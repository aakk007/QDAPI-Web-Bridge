package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.app.Dialog;
import android.content.Intent;
import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Collections;
import java.util.Set;

// 只观察原模块的成功结果，不补造奖励、不按固定延时中断广告。
final class SilentRewardAd {
    private static final String CALLBACK = "cn.xihan.qdds.hook.MainHook$RewardVideoCallbacks";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> rewardActivity = new WeakReference<>(null);
    private static boolean installed;
    private static boolean installing;
    private static XC_MethodHook.Unhook loaderHook;
    private static volatile boolean startHooks;
    private static volatile Session session;
    private static final Map<Activity, Presentation> hidden = new WeakHashMap<>();
    private static final Map<Object, WeakReference<Object>> klevinListeners = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Class<?>> klevinTypes = Collections.newSetFromMap(new WeakHashMap<>());

    private static final class Session {
        final Object ad;
        final Object listener;
        final long started = SystemClock.uptimeMillis();
        volatile boolean success;
        Session(Object ad, Object listener) { this.ad = ad; this.listener = listener; }
    }

    private static final class Presentation {
        final Session owner;
        final RewardPresentation surface;
        Presentation(Activity activity, Session owner) {
            this.owner = owner;
            surface = new RewardPresentation(activity.getWindow());
        }
    }

    private SilentRewardAd() {}

    static synchronized void watchModule(ClassLoader hostLoader) {
        installStartHooks(hostLoader);
        tryModule(hostLoader);
        findLoadedModule();
        if (installed || loaderHook != null) return;
        try {
            loaderHook = XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.hasThrowable() || !(hook.getResult() instanceof Class)) return;
                    String name = (String) hook.args[0];
                    if (!name.startsWith("cn.xihan.qdds.")) return;
                    tryModule(((Class<?>) hook.getResult()).getClassLoader());
                }
            });
        } catch (Throwable error) {
            Log.w("QDAPI-Cloud", "秒过结果监听暂不可用，保留原模块广告流程", error);
        }
    }

    private static void findLoadedModule() {
        // 当前设备使用混淆后的现代 LSPosed；只读取匹配包名的已加载模块，不创建第二份模块实例。
        for (String name : new String[]{"org.lsposed.lspd.impl.LSPosedContext", "y0"}) {
            if (installed) return;
            try {
                Class<?> context = Class.forName(name, false, XposedBridge.class.getClassLoader());
                for (Field registry : context.getDeclaredFields()) {
                    if (!Modifier.isStatic(registry.getModifiers()) || !Map.class.isAssignableFrom(registry.getType())) continue;
                    registry.setAccessible(true);
                    Object value = registry.get(null);
                    if (!(value instanceof Map)) continue;
                    for (Object owner : ((Map<?, ?>) value).values()) {
                        if (owner == null || !context.isInstance(owner)) continue;
                        boolean matches = false;
                        for (Field field : context.getDeclaredFields()) {
                            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                            field.setAccessible(true);
                            if ("cn.xihan.qdds".equals(field.get(owner))) matches = true;
                        }
                        if (!matches) continue;
                        for (Field field : context.getDeclaredFields()) {
                            if (Modifier.isStatic(field.getModifiers()) || !Iterable.class.isAssignableFrom(field.getType())) continue;
                            field.setAccessible(true);
                            Object modules = field.get(owner);
                            if (!(modules instanceof Iterable)) continue;
                            for (Object module : (Iterable<?>) modules) {
                                if (module != null) tryModule(module.getClass().getClassLoader());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 框架版本不匹配时仍可通过后续类加载/原生设置页接入。
            }
        }
    }

    static synchronized void tryModule(ClassLoader loader) {
        if (installed || installing || loader == null) return;
        installing = true;
        try {
            Class<?> type = Class.forName(CALLBACK, false, loader);
            XposedHelpers.findAndHookMethod(type, "fireReward", Object.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.hasThrowable() || !(hook.getResult() instanceof Enum)) return;
                    // ALREADY_FIRED 是旧回调，不能用来关闭新一轮视频。
                    if (!"SUCCESS".equals(((Enum<?>) hook.getResult()).name())) return;
                    Session current = session;
                    // 旧监听器的延迟成功不能关闭新一轮广告。
                    if (current == null || hook.args[0] != current.listener) return;
                    current.success = true;
                    MAIN.post(() -> {
                        Activity activity = rewardActivity.get();
                        if (activity != null && hidden.containsKey(activity) && hidden.get(activity).owner == current)
                            finishReward(activity);
                    });
                }
            });
            installed = true;
            if (loaderHook != null) {
                loaderHook.unhook();
                loaderHook = null;
            }
            Log.i("QDAPI-Cloud", "原模块秒过成功结果监听已启用");
        } catch (ClassNotFoundException ignored) {
            // 原模块有独立加载器，等它加载类或进入其设置页后再连接。
        } catch (Throwable error) {
            Log.w("QDAPI-Cloud", "原模块秒过回调未适配，保留原广告流程", error);
        } finally {
            installing = false;
        }
    }

    static boolean isRewardActivity(String name) {
        return RewardAdPolicy.isRewardActivity(name);
    }

    static boolean ready() { return installed && startHooks; }

    private static void begin(Object ad, Object listener) {
        session = installed && listener != null ? new Session(ad, listener) : null;
        Session current = session;
        if (current != null) MAIN.postDelayed(() -> { if (session == current) session = null; }, 60000);
    }

    private static void installStartHooks(ClassLoader loader) {
        if (startHooks) return;
        try {
            Class<?> gdt = XposedHelpers.findClass("com.qq.e.tg.rewardAD.TangramRewardAD", loader);
            Class<?> listener = XposedHelpers.findClass("com.qq.e.tg.rewardAD.TangramRewardADListener", loader);
            XposedHelpers.findAndHookMethod(gdt, "setVideoVolumeOn", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (installed) hook.args[0] = false;
                }
            });
            XposedBridge.hookAllConstructors(gdt, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (installed) XposedHelpers.callMethod(hook.thisObject, "setVideoVolumeOn", false);
                }
            });
            XposedHelpers.findAndHookMethod(gdt, "showAD", new XC_MethodHook(10000) {
                @Override protected void beforeHookedMethod(MethodHookParam hook) throws Throwable {
                    if (!installed) return;
                    session = null;
                    XposedHelpers.callMethod(hook.thisObject, "setVideoVolumeOn", false);
                    for (Field field : gdt.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers()) && listener.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            begin(hook.thisObject, field.get(hook.thisObject));
                            break;
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(gdt, "showAD", new XC_MethodHook(-10000) {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    Session current = session;
                    // 原模块已同步完成时不再进入SDK展示；这里不生成任何完成事件。
                    if (installed && current != null && current.ad == hook.thisObject && current.success) hook.setResult(null);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!ready() || session == null) return;
                    for (Object arg : hook.args) if (arg instanceof Intent) {
                        Intent intent = (Intent) arg;
                        if (intent.getComponent() != null && isRewardActivity(intent.getComponent().getClassName()))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!ready() || !inAdHelper()) return;
                    Dialog dialog = (Dialog) hook.thisObject;
                    if (dialog.getWindow() == null) return;
                    RewardPresentation loading = new RewardPresentation(dialog.getWindow());
                    loading.hide();
                    MAIN.postDelayed(() -> {
                        if (dialog.isShowing() && dialog.getWindow() != null) {
                            loading.restore();
                        }
                    }, 2000);
                }
            });
            startHooks = true;
            Log.i("QDAPI-Cloud", "广点通广告启动静默与SDK静音已启用");
        } catch (Throwable error) {
            Log.w("QDAPI-Cloud", "广告启动适配未完成，保留原SDK流程");
        }
        installKlevin(loader);
    }

    private static boolean inAdHelper() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("com.qidian.QDReader.extras.r0".equals(frame.getClassName()) && "c1".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    private static void installKlevin(ClassLoader loader) {
        try {
            Class<?> request = XposedHelpers.findClass("com.tencent.klevin.ads.ad.RewardAdRequest", loader);
            XposedHelpers.findAndHookMethod(request, "isAutoMute", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (installed) hook.setResult(true);
                }
            });
            Class<?> ad = XposedHelpers.findClass("com.tencent.klevin.ads.ad.RewardAd", loader);
            Class<?> listener = XposedHelpers.findClass("com.tencent.klevin.ads.ad.RewardAd$RewardAdListener", loader);
            XposedBridge.hookAllConstructors(ad, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                    Class<?> type = hook.thisObject.getClass();
                    synchronized (klevinTypes) { if (!klevinTypes.add(type)) return; }
                    XposedBridge.hookMethod(type.getMethod("setListener", listener), new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            klevinListeners.put(call.thisObject, new WeakReference<>(call.args[0]));
                        }
                    });
                    XposedBridge.hookMethod(type.getMethod("show"), new XC_MethodHook(10000) {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            WeakReference<Object> listenerRef = klevinListeners.get(call.thisObject);
                            begin(call.thisObject, listenerRef == null ? null : listenerRef.get());
                        }
                    });
                }
            });
        } catch (Throwable ignored) { Log.i("QDAPI-Cloud", "当前Klevin启动入口未适配，保留原流程"); }
    }

    static void created(Activity activity) {
        if (!isRewardActivity(activity.getClass().getName())) return;
        rewardActivity = new WeakReference<>(activity);
        Presentation existing = hidden.get(activity);
        if (existing != null) { existing.surface.hide(); activity.setVisible(false); return; }
        Session current = session;
        if (!ready() || current == null || SystemClock.uptimeMillis() - current.started > 2000) return;
        Presentation state = new Presentation(activity, current);
        hidden.put(activity, state);
        state.surface.hide();
        activity.setVisible(false);
        activity.overridePendingTransition(0, 0);
        if (current.success) MAIN.post(() -> finishReward(activity));
        // 未秒过时恢复页面，不能留下不可见的交互页或强行假装完成。
        MAIN.postDelayed(() -> {
            if (hidden.get(activity) != state || activity.isFinishing() || activity.isDestroyed()) return;
            if (current.success) { finishReward(activity); return; }
            restore(activity);
            Log.i("QDAPI-Cloud", "本次广告未即时完成，已恢复正常页面；未报奖励成功");
        }, 2000);
    }

    static void paused(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            hidden.remove(activity);
            if (rewardActivity.get() == activity) rewardActivity.clear();
        }
    }

    private static void restore(Activity activity) {
        Presentation state = hidden.remove(activity);
        if (state == null) return;
        state.surface.restore();
        activity.setVisible(true);
    }

    private static void finishReward(Activity activity) {
        if (rewardActivity.get() != activity || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            // 成功后隐藏收尾帧并关闭；不改变全局媒体音量或其他页面。
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            attributes.alpha = 0f;
            activity.getWindow().setAttributes(attributes);
            activity.finish();
            activity.overridePendingTransition(0, 0);
            hidden.remove(activity);
            rewardActivity.clear();
            Log.i("QDAPI-Cloud", "原模块秒过成功，已静默收起激励视频页；非官方到账凭证");
        } catch (Throwable error) {
            Log.w("QDAPI-Cloud", "激励视频收尾失败，交由原模块处理", error);
        }
    }
}
