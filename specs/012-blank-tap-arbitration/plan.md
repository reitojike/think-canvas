# 空白 tap arbitration 設計

既存 central pointer handler 内の空白 tap 入口だけを変更する。別 detectTapGestures / pointerInput を追加しない。
local pending identity、first UP timestamp、screen / world 座標、gesture generation、confirmation Job を保持する。
LocalViewConfiguration の doubleTapTimeoutMillis / doubleTapMinTimeMillis と Android scaledDoubleTapSlop を使い、second DOWN で timing / slop を判定する。
無効な second DOWN は first を confirmed single として一度確定してから、最新 editor / selection authority で次の gesture の可否を判断する。Android GestureDetector の空白外連続 tap で first confirmed callback が消える部分は採用せず、明示された first action を保持する。

Job は raw editorSession / saveState と現在の interaction、first の generation / content を再確認する。observer と STOP/dispose および既存 invalidation で破棄する。保存・schema・dependency・workflow は変更しない。
tap 前から成立した region name editor は captured context として保持し、同じ session / 内容のままなら従来の selection clear を confirmed single で実行する。pending 中の開始・入力変更・終了は取消す。成立済み text editor の #71 admission は維持する。
Constitution の standard-first と位置保持を満たす。first world 座標を再投影しない。editor lifecycle と保存の責務は変更しない。

現行 Task Contract は MERGE_READY 確認後の merge まで。Issue close は含まない。

標準 timing/slop の参照: [Android GestureDetector](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/GestureDetector.java)。
