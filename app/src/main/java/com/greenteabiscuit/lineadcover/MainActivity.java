package com.greenteabiscuit.lineadcover;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(getColor(R.color.app_background));

        TextView title = text(getString(R.string.title), 28, true);
        content.addView(title);
        content.addView(text(getString(R.string.explanation), 17, false));
        content.addView(text(getString(R.string.privacy), 15, false));
        status = text("", 17, true);
        content.addView(status);

        Button accessibility = new Button(this);
        accessibility.setText(R.string.open_accessibility);
        accessibility.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        content.addView(accessibility, matchWidth());

        Button line = new Button(this);
        line.setText(R.string.open_line);
        line.setOnClickListener(v -> openApp(LINE_PACKAGE, R.string.line_not_installed));
        content.addView(line, matchWidth());

        Button wechat = new Button(this);
        wechat.setText(R.string.open_wechat);
        wechat.setOnClickListener(v -> openApp(WECHAT_PACKAGE, R.string.wechat_not_installed));
        content.addView(wechat, matchWidth());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean enabled = isServiceEnabled();
        status.setText(enabled ? R.string.status_enabled : R.string.status_disabled);
        status.setTextColor(enabled ? getColor(R.color.line_green) : Color.rgb(180, 45, 45));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, LineAdCoverService.class);
        for (String entry : enabled.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private void openApp(String packageName, int notInstalledMessage) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Toast.makeText(this, notInstalledMessage, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(intent);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(30, 32, 35));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setPadding(0, 0, 0, Math.round(18 * getResources().getDisplayMetrics().density));
        return view;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
