package com.zj.sshapp

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.emulatorview.TermSession

/**
 * B版：全屏终端模拟器
 * JSch 交互式 shell 通道（PTY）+ VT-100 终端渲染，可跑 vim/top，真光标、真颜色。
 */
class TerminalActivity : Activity() {

    private var termSession: TermSession? = null
    private var emulatorView: EmulatorView? = null
    private var jschSession: Session? = null
    private var channel: ChannelShell? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getIntExtra("port", 22)
        val user = intent.getStringExtra("user") ?: ""
        val pwd = intent.getStringExtra("pwd") ?: ""

        // 终端会话 + 渲染视图（黑底，等宽字体，闪烁光标由库渲染）
        termSession = TermSession()
        emulatorView = EmulatorView(this, termSession!!, resources.displayMetrics).apply {
            setTextSize(16)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(emulatorView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)

        connect(host, port, user, pwd)
    }

    private fun connect(host: String, port: Int, user: String, pwd: String) {
        Thread {
            try {
                val session = JSch().getSession(user, host, port)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setPassword(pwd)
                session.connect(15000)
                val shell = session.openChannel("shell") as ChannelShell
                shell.setPtyType("xterm")
                shell.connect(15000)
                val ins = shell.inputStream   // 服务器 -> 终端
                val outs = shell.outputStream // 终端 -> 服务器
                runOnUiThread {
                    termSession?.setTermIn(ins)
                    termSession?.setTermOut(outs)
                    termSession?.initializeEmulator(80, 24)
                    emulatorView?.requestFocus()
                }
                jschSession = session
                channel = shell
            } catch (e: Exception) {
                runOnUiThread { toast("连接失败: ${e.message}") }
            }
        }.start()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { channel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
    }
}
