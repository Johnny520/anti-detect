package com.antidetect.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileWriter;

public class MainActivity extends AppCompatActivity {

    private static final String CONFIG_PATH = "/data/data/com.antidetect.app/files/antidetect_config.txt";

    private Switch swEnabled;
    private EditText etWhitelist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnabled = findViewById(R.id.sw_enabled);
        etWhitelist = findViewById(R.id.et_whitelist);
        Button btnSave = findViewById(R.id.btn_save);
        TextView tvHint = findViewById(R.id.tv_hint);

        tvHint.setText("在 LSPosed 中勾选本模块并选择作用域（需要隐藏 Root 的 App）后生效。\n" +
                "配置保存在模块私有目录，钩子进程会自动读取。");

        // 读现有配置
        Config cfg = readConfig();
        swEnabled.setChecked(cfg.enabled);
        etWhitelist.setText(String.join("\n", cfg.whitelist));

        btnSave.setOnClickListener(v -> {
            boolean enabled = swEnabled.isChecked();
            String[] lines = etWhitelist.getText().toString().split("\\n");
            StringBuilder sb = new StringBuilder();
            sb.append("enabled=").append(enabled ? "1" : "0").append("\n");
            sb.append("whitelist=");
            boolean first = true;
            for (String l : lines) {
                String p = l.trim();
                if (!p.isEmpty() && !p.startsWith("#")) {
                    if (!first) sb.append(",");
                    sb.append(p);
                    first = false;
                }
            }
            sb.append("\n");
            if (saveConfig(sb.toString())) {
                Toast.makeText(this, "已保存，重启目标 App 生效", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败：无法写入配置文件", Toast.LENGTH_LONG).show();
            }
        });
    }

    private Config readConfig() {
        Config c = new Config();
        c.enabled = true;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(CONFIG_PATH))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("enabled=")) {
                    c.enabled = !line.substring("enabled=".length()).trim().startsWith("0");
                } else if (line.startsWith("whitelist=")) {
                    for (String p : line.substring("whitelist=".length()).split(",")) {
                        p = p.trim();
                        if (!p.isEmpty()) c.whitelist.add(p);
                    }
                } else {
                    c.whitelist.add(line);
                }
            }
        } catch (Throwable ignored) {}
        return c;
    }

    private boolean saveConfig(String content) {
        try {
            File f = new File(CONFIG_PATH);
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f)) {
                w.write(content);
            }
            // 让钩子进程（其他 App）能读
            Runtime.getRuntime().exec("chmod 644 " + f.getAbsolutePath()).waitFor();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static class Config {
        boolean enabled;
        java.util.Set<String> whitelist = new java.util.HashSet<>();
    }
}
