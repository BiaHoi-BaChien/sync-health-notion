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
                text = "手入力した血圧と心拍はHealth Connectへ保存します。同期時はHealth Connectの歩数、血圧、心拍データをNotionへ送信します。"
                textSize = 18f
            })
        }
        setContentView(view)
    }
}
