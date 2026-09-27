package com.thinkcanvas.data

import android.content.Context

/** 画面テストの開始対象を明示し、前のテストの最終閲覧状態に依存しない。 */
fun showBoardOneAtStartup(context: Context) {
    check(context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE).edit()
        .putBoolean("initialized", true)
        .putBoolean("guideDismissed", true)
        .putLong("lastOpenedBoardId", 1)
        .commit())
}
