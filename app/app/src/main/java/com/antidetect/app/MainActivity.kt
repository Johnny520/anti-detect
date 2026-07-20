package com.antidetect.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 反检测设置 App（本机自带，模块对它自己不隐藏，所以它自己能正常调用 su 写配置）
 * - 总开关：开/关整个反检测
 * - 列表勾选：对这些 App "不隐藏"（需要它们正常识别 Root 的 App 才勾，比如某些依赖 Root 的工具）
 */
data class AppItem(val pkg: String, val label: String, var checked: Boolean)

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var switch: Switch
    private lateinit var saveBtn: Button
    private val items = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listView)
        switch = findViewById(R.id.switchEnable)
        saveBtn = findViewById(R.id.btnSave)

        switch.isChecked = readEnabled()
        loadApps()

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice) {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): String = "${items[position].label}\n${items[position].pkg}"
            override fun getItemId(position: Int): Long = position.toLong()
        }
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        for (i in items.indices) listView.setItemChecked(i, items[i].checked)

        saveBtn.setOnClickListener { save() }
    }

    private fun loadApps() {
        val white = readWhite()
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (ai in installed) {
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val pkg = ai.packageName
            if (pkg == "com.antidetect.app") continue
            val label = pm.getApplicationLabel(ai).toString()
            items.add(AppItem(pkg, label, white.contains(pkg)))
        }
        items.sortBy { it.label.lowercase() }
    }

    /** 通过 su 执行命令（本 App 在模块白名单里，su 对它可见） */
    private fun runSu(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            ""
        }
    }

    private fun readWhite(): Set<String> {
        val out = runSu("cat /data/adb/anti_detect/white.list 2>/dev/null")
        return out.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private fun readEnabled(): Boolean {
        val out = runSu("cat /data/adb/anti_detect/enabled 2>/dev/null").trim()
        return out != "0"
    }

    private fun save() {
        val enable = if (switch.isChecked) "1" else "0"
        val sb = StringBuilder()
        sb.append("mkdir -p /data/adb/anti_detect; ")
        sb.append("echo $enable > /data/adb/anti_detect/enabled; ")
        sb.append("echo -n > /data/adb/anti_detect/white.list; ")
        for (i in items.indices) {
            if (listView.isItemChecked(i)) {
                sb.append("echo ${items[i].pkg} >> /data/adb/anti_detect/white.list; ")
            }
        }
        runSu(sb.toString())
        Toast.makeText(this, "已保存，重启目标 App 后生效", Toast.LENGTH_SHORT).show()
    }
}
