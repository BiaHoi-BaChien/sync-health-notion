package net.biahoi.stepnotionsync

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "同期ボタンを押した時だけ、Health Connectの歩数をNotionへ送信し、Notionの血圧と心拍データをHealth Connectへ保存します。"
                textSize = 18f
            })
        }
        setContentView(view)
    }
}
