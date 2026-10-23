package com.zj.sshapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    data class Server(val name: String, val host: String, val port: Int,
                      val user: String, val pwd: String)

    data class Cmd(val title: String, val desc: String, val cmd: String)

    // 常用命令库（分类 -> 命令列表），可继续往下加
    val categories = linkedMapOf(
        "Docker 容器" to listOf(
            Cmd("查看运行容器", "列出正在运行的容器", "docker ps"),
            Cmd("重启影视 moontv", "重启 Moontv 影视服务", "docker restart moontv-core"),
            Cmd("影视日志", "查看影视最近50行日志", "docker logs --tail 50 moontv-core"),
            Cmd("容器资源占用", "看CPU/内存占用一次", "docker stats --no-stream"),
        ),
        "系统状态" to listOf(
            Cmd("磁盘占用", "看各分区还剩多少空间", "df -h"),
            Cmd("内存占用", "看内存和交换分区", "free -h"),
            Cmd("运行时长", "开机多久、负载高不高", "uptime"),
        ),
        "服务管理" to listOf(
            Cmd("Docker服务状态", "docker 服务是否正常运行", "systemctl status docker --no-pager"),
            Cmd("面板状态", "1panel 面板状态", "systemctl status 1panel --no-pager"),
        ),
    )

    private lateinit var container: FrameLayout
    private var servers = mutableListOf<Server>()
    private var current: Server? = null
    private lateinit var cmdInput: EditText
    private lateinit var outView: TextView

    @Suppress("UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)

        servers = loadServers()

        findViewById<Button>(R.id.tabHome).setOnClickListener { showHome() }
        findViewById<Button>(R.id.tabCmd).setOnClickListener { showCmd() }
        findViewById<Button>(R.id.tabMore).setOnClickListener { showMore() }

        showHome()
    }

    // ---------- 数据存取 ----------
    private fun loadServers(): MutableList<Server> {
        val sp = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val raw = sp.getString("servers", "[]") ?: "[]"
        val list = mutableListOf<Server>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Server(o.getString("name"), o.getString("host"),
                o.getInt("port"), o.getString("user"), o.getString("pwd")))
        }
        return list
    }

    private fun saveServers() {
        val arr = JSONArray()
        for (s in servers) {
            arr.put(JSONObject().put("name", s.name).put("host", s.host)
                .put("port", s.port).put("user", s.user).put("pwd", s.pwd))
        }
        getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .edit().putString("servers", arr.toString()).apply()
    }

    // ---------- 主页：服务器列表 ----------
    private fun showHome() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val addBtn = Button(this).apply { text = "+ 添加服务器" }
        addBtn.setOnClickListener { showAddDialog() }
        root.addView(addBtn)

        if (servers.isEmpty()) {
            root.addView(TextView(this).apply { text = "还没有服务器，点上面添加" })
        }
        for (s in servers) {
            val tv = TextView(this).apply {
                text = "${s.name}\n${s.host}:${s.port}  (${s.user})"
                textSize = 15f
                setPadding(12, 18, 12, 18)
            }
            tv.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(s.name)
                    .setMessage("${s.host}:${s.port}  (${s.user})\n选择连接模式：")
                    .setPositiveButton("终端模式(B版)") { _, _ -> startTerminal(s) }
                    .setNeutralButton("命令行模式(A版)") { _, _ -> current = s; showHome() }
                    .setNegativeButton("取消", null)
                    .show()
            }
            root.addView(tv)
        }

        // 选中后的执行区
        if (current != null) {
            root.addView(horizontalLine())
            root.addView(TextView(this).apply {
                text = "当前: ${current!!.name}"; typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            cmdInput = EditText(this).apply {
                hint = "输入命令，回车直接执行"
                setText("docker ps")
                // 单行模式：回车不再换行，而是触发“执行”
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEND
                setOnEditorActionListener { _, actionId, event ->
                    val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
                    if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                        runCurrent()
                        true
                    } else false
                }
            }
            root.addView(cmdInput)
            val runBtn = Button(this).apply { text = "执行" }
            runBtn.setOnClickListener { runCurrent() }
            root.addView(runBtn)
            outView = TextView(this).apply {
                setTextColor(0xFF222222.toInt()); textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                text = "结果显示在这里"
            }
            val sv = ScrollView(this)
            sv.addView(outView)
            root.addView(sv)
        }

        container.removeAllViews()
        container.addView(root)
    }

    private fun horizontalLine(): View {
        val v = View(this)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
        v.setBackgroundColor(0xFFDDDDDD.toInt())
        return v
    }

    private fun showAddDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0)
        }
        val eName = edit("名称，如：家里虚拟机", "电脑虚拟机")
        val eHost = edit("地址 IP", "192.168.88.100")
        val ePort = edit("端口", "22")
        val eUser = edit("用户名", "root")
        val ePwd = edit("密码", "admin123")
        listOf(eName, eHost, ePort, eUser, ePwd).forEach { lay.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("添加服务器")
            .setView(lay)
            .setPositiveButton("保存") { _, _ ->
                servers.add(Server(eName.text.toString().ifEmpty { "未命名" },
                    eHost.text.toString(),
                    ePort.text.toString().toIntOrNull() ?: 22,
                    eUser.text.toString().ifEmpty { "root" },
                    ePwd.text.toString()))
                saveServers(); showHome()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun edit(hint: String, def: String): EditText =
        EditText(this).apply { this.hint = hint; setText(def) }

    // ---------- 指令页：常用命令 ----------
    private fun showCmd() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "点命令：填入输入框；长按：复制"; textSize = 13f
        })

        for ((cat, cmds) in categories) {
            root.addView(TextView(this).apply {
                text = "【$cat】"; typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 16f
                setPadding(0, 20, 0, 6)
            })
            for (c in cmds) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(8, 14, 8, 14)
                }
                val info = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = "${c.title}\n${c.desc}\n$ ${c.cmd}"
                    textSize = 14f
                }
                info.setOnClickListener {
                    if (current == null) toast("先去主页选一台服务器")
                    else { cmdInput.setText(c.cmd); toast("已填入，去主页点执行") }
                }
                info.setOnLongClickListener { copy(c.cmd); true }
                val cp = Button(this).apply { text = "复制" }
                cp.setOnClickListener { copy(c.cmd) }
                item.addView(info); item.addView(cp)
                root.addView(item)
            }
        }
        scroll.addView(root)
        container.removeAllViews()
        container.addView(scroll)
    }

    private fun showMore() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "版本 1.0\n点命令长按可复制；服务器在主页添加。" })
        val clear = Button(this).apply { text = "清空已保存服务器" }
        clear.setOnClickListener {
            servers.clear(); current = null; saveServers(); showHome()
        }
        root.addView(clear)
        container.removeAllViews()
        container.addView(root)
    }

    private fun startTerminal(s: Server) {
        val i = Intent(this, TerminalActivity::class.java)
        i.putExtra("name", s.name).putExtra("host", s.host).putExtra("port", s.port)
            .putExtra("user", s.user).putExtra("pwd", s.pwd)
        startActivity(i)
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cmd", text))
        toast("已复制: $text")
    }

    private fun toast(m: String) =
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- 执行 ----------
    private fun runCurrent() {
        val s = current ?: run { toast("先选服务器"); return }
        val cmd = cmdInput.text.toString().trim()
        if (cmd.isEmpty()) return
        outView.text = "执行中..."
        Thread {
            val res = execSsh(s, cmd)
            runOnUiThread { outView.text = colorize(res) }
        }.start()
    }

    // 重要信息着色：绿色=成功/重要，红色=错误/失败，其余默认色
    private fun colorize(text: String): CharSequence {
        val sp = SpannableString(text)
        val okRe = Regex("成功|完成|连接成功|已连接|UP|active|running|ok\\b|OK\\b|无输出|正常|已启动", RegexOption.IGNORE_CASE)
        val errRe = Regex("失败|错误|error|failed|拒绝|denied|timed ?out|超时|not found|no such|无法|exception", RegexOption.IGNORE_CASE)
        val default = 0xFF222222.toInt()
        val green = Color.parseColor("#00C853")
        val red = Color.parseColor("#E53935")
        val lines = text.split("\n")
        var offset = 0
        for (line in lines) {
            val trimmed = line.trim()
            var color = default
            if (errRe.containsMatchIn(trimmed)) color = red
            else if (okRe.containsMatchIn(trimmed)) color = green
            if (color != default && line.isNotEmpty()) {
                sp.setSpan(ForegroundColorSpan(color), offset, offset + line.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset += line.length + 1
        }
        return sp
    }

    private fun execSsh(s: Server, cmd: String): String {
        return try {
            val session = JSch().getSession(s.user, s.host, s.port)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setPassword(s.pwd)
            session.connect(10000)
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(cmd)
            val ins = channel.inputStream
            channel.connect(10000)
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                sb.append(String(buf, 0, n))
            }
            channel.disconnect(); session.disconnect()
            if (sb.isBlank()) "(完成，无输出)" else sb.toString()
        } catch (e: Exception) {
            "连接失败: ${e.message}"
        }
    }
}
