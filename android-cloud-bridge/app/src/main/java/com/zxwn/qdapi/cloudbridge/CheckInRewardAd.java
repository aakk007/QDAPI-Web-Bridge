package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// 由本机任务引擎查询资格后调用；不再扫描或点击弹层，不生成广告完成事件。
final class CheckInRewardAd {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile ClassLoader loader;
    private static WeakReference<Activity> foreground = new WeakReference<>(null);

    static void install(ClassLoader host) {
        try {
            Class.forName("com.qidian.QDReader.extras.r0", false, host)
                    .getMethod("d1", Activity.class, String.class, String.class);
            Class<?> consumer = Class.forName("mo.d", false, host);
            Class<?> action = Class.forName("mo.search", false, host);
            Class.forName("io.reactivex.r", false, host).getMethod("subscribe", consumer, consumer, action);
            Class.forName("com.qidian.QDReader.component.config.QDAppConfigHelper", false, host).getMethod("Q0", String.class);
            Class.forName("io.reactivex.disposables.judian", false, host).getMethod("dispose");
            loader = host;
            Log.i("QDAPI-Cloud", "签到广告接口补领已启用，宿主方法签名验证通过，不依赖弹层");
        } catch (Throwable error) {
            loader = null;
            Log.w("QDAPI-Cloud", "宿主签到广告接口不兼容，本机仅查询资格，不启动广告");
        }
    }

    static void resumed(Activity activity) {
        if (activity.getClass().getName().startsWith("com.qidian.QDReader.")
                && !SilentRewardAd.isRewardActivity(activity.getClass().getName())) foreground = new WeakReference<>(activity);
    }

    static void paused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    static String watch(LocalEngineRunner.Permit permit) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new Exception("广告请求不能阻塞主线程");
        if (!running.compareAndSet(false, true)) throw new Exception("签到广告正在执行");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ended = new AtomicBoolean();
        AtomicBoolean rewarded = new AtomicBoolean();
        AtomicReference<Object> disposable = new AtomicReference<>();
        AtomicReference<String> adID = new AtomicReference<>("");
        try {
            permit.check();
            MAIN.post(() -> {
                if (ended.get()) return;
                Activity activity = foreground.get();
                try {
                    if (loader == null || activity == null || activity.isFinishing() || activity.isDestroyed()
                            || !SilentRewardAd.ready()) throw new Exception("宿主前台或静默适配尚未就绪");
                    Class<?> consumer = Class.forName("mo.d", false, loader);
                    Class<?> action = Class.forName("mo.search", false, loader);
                    Object next = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
                        if ("accept".equals(method.getName()) && !ended.get() && args != null
                                && args[0] instanceof Integer && ((Integer) args[0]) == 5) {
                            Class<?> config = Class.forName("com.qidian.QDReader.component.config.QDAppConfigHelper", false, loader);
                            Object id = config.getMethod("Q0", String.class).invoke(null, "qd_signup");
                            if (id instanceof String) adID.set((String) id);
                            rewarded.set(true);
                            done.countDown();
                        }
                        return proxyObject(proxy, method.getName(), args);
                    });
                    Object failure = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
                        if ("accept".equals(method.getName())) done.countDown();
                        return proxyObject(proxy, method.getName(), args);
                    });
                    Object complete = Proxy.newProxyInstance(loader, new Class<?>[]{action}, (proxy, method, args) -> {
                        if ("run".equals(method.getName())) done.countDown();
                        return proxyObject(proxy, method.getName(), args);
                    });
                    Class<?> helper = Class.forName("com.qidian.QDReader.extras.r0", false, loader);
                    Object observable = helper.getMethod("d1", Activity.class, String.class, String.class)
                            .invoke(null, activity, "1108323910", "qd_signup");
                    Class<?> observableType = Class.forName("io.reactivex.r", false, loader);
                    disposable.set(observableType.getMethod("subscribe", consumer, consumer, action)
                            .invoke(observable, next, failure, complete));
                    if (ended.get()) dispose(disposable.get());
                } catch (Throwable error) {
                    Log.w("QDAPI-Cloud", "宿主签到广告未完成，未提交奖励");
                    done.countDown();
                }
            });
            if (!done.await(40, TimeUnit.SECONDS) || !rewarded.get() || adID.get().isEmpty())
                throw new Exception("未取得宿主签到广告完成事件，未提交奖励");
            permit.check();
            return adID.get();
        } finally {
            ended.set(true);
            MAIN.post(() -> dispose(disposable.get()));
            running.set(false);
        }
    }

    private static Object proxyObject(Object proxy, String method, Object[] args) {
        if ("hashCode".equals(method)) return System.identityHashCode(proxy);
        if ("equals".equals(method)) return args != null && args.length == 1 && proxy == args[0];
        if ("toString".equals(method)) return "QDAPI check-in observer";
        return null;
    }

    private static void dispose(Object value) {
        if (value == null || loader == null) return;
        try { Class.forName("io.reactivex.disposables.judian", false, loader).getMethod("dispose").invoke(value); }
        catch (Throwable ignored) { Log.w("QDAPI-Cloud", "签到广告观察器结束；宿主自行回收未适配的订阅"); }
    }
}
