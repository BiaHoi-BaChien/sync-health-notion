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
                text = "手入力した血圧、脈拍、体重はHealth Connectへ保存します。同期設定に応じて、歩数、血圧、脈拍、体重データをHealth ConnectとNotionの間で読み書きします。歩数の達成通知がONの場合は、今日の歩数を読み取り、目標の25％・50％・75％・100％の達成を通知します。定期通知にはバックグラウンドでの読み取りを使用します。"
                textSize = 18f
            })
        }
        setContentView(view)
    }
}
