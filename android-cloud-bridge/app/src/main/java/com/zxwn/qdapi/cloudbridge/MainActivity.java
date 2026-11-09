package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String PREFS = "bridge_ui";
    private static final String HIDE_LAUNCHER = "hide_launcher";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,48,32,32);
        TextView text = new TextView(this);text.setTextSize(18);text.setText(getString(R.string.bridge_instructions,BuildConfig.VERSION_NAME));root.addView(text);
        Button credits = new Button(this);credits.setText("来源、作者与许可");credits.setOnClickListener(v->ProjectCredits.show(this));root.addView(credits);
        Button launcher = new Button(this);
        boolean hidden = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(HIDE_LAUNCHER, false);
        if (!applyLauncherVisibility(!hidden)) hidden = isLauncherHidden();
        refreshLauncherButton(launcher, hidden);
        launcher.setOnClickListener(v -> {
            boolean nowHidden = !isLauncherHidden();
            if (!applyLauncherVisibility(!nowHidden)) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(HIDE_LAUNCHER, nowHidden).apply();
            refreshLauncherButton(launcher, nowHidden);
            Toast.makeText(this, nowHidden ? "桌面图标已隐藏，可从 LSPosed 模块设置重新打开" : "桌面图标已显示", Toast.LENGTH_LONG).show();
        });
        root.addView(launcher);
        TextView launcherHelp = new TextView(this);launcherHelp.setText("隐藏后可从 LSPosed → 模块 → QDAPI 云端桥接 → 设置进入，再点击「显示桌面图标」。部分桌面需要稍等或重新打开桌面才会刷新。");root.addView(launcherHelp);
        Button open = new Button(this);open.setText("打开起点");open.setOnClickListener(v->{android.content.Intent intent=getPackageManager().getLaunchIntentForPackage("com.qidian.QDReader");if(intent!=null)startActivity(intent);});root.addView(open);
        ScrollView scroll = new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }

    private void refreshLauncherButton(Button button, boolean hidden) {
        button.setText(hidden ? "显示桌面图标" : "隐藏桌面图标");
    }

    private boolean isLauncherHidden() {
        return getPackageManager().getComponentEnabledSetting(new ComponentName(this, getPackageName() + ".Launcher"))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private boolean applyLauncherVisibility(boolean visible) {
        try {
            if (isLauncherHidden() == !visible) return true;
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, getPackageName() + ".Launcher"),
                    visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        } catch (RuntimeException error) {
            Toast.makeText(this, "桌面图标设置失败，请重试", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
