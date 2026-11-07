package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,48,32,32);
        TextView text = new TextView(this);text.setTextSize(18);text.setText(getString(R.string.bridge_instructions,BuildConfig.VERSION_NAME));root.addView(text);
        Button credits = new Button(this);credits.setText("来源、作者与许可");credits.setOnClickListener(v->ProjectCredits.show(this));root.addView(credits);
        Button open = new Button(this);open.setText("打开起点");open.setOnClickListener(v->{android.content.Intent intent=getPackageManager().getLaunchIntentForPackage("com.qidian.QDReader");if(intent!=null)startActivity(intent);});root.addView(open);setContentView(root);
    }
}
