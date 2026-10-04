# 調査と判断

- **Decision**: Backは変更ありだけ標準AlertDialogで破棄確認。**Rationale**: PO確認済み。**Alternatives**: 自動Doneは採用しない。outsideはSpec 006を維持。
- **Decision**: text/regionは既存board session、小さいtool/確認識別はrememberSaveable。**Rationale**: [Android state saving](https://developer.android.com/develop/ui/compose/state-saving)のViewModelとsaved-instance-stateの役割に合わせる。**Alternatives**: schema保存・generic lifecycle managerは不要。
- **Decision**: ink blank tapはexitにしない。**Rationale**: Spec 003 FR-003は指1本が描画で、最短strokeと終了が衝突する。**Alternatives**: 独自tap/dot閾値は未承認の描画意味変更。
- **Decision**: [BackHandler](https://developer.android.com/reference/kotlin/androidx/activity/compose/package-summary)と標準dialogを使い、tool exitでpointerInputをcancelする。**Rationale**: 古いUPのcommitを防ぐ。**Alternatives**: gesture全面rewriteは不要。
- dependency追加なし。未解決technical clarificationなし。
- Issue91のIME復帰: [Android公式のwindow/input connection条件](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility#show-reliably)に合わせ、[Compose WindowInfo](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/WindowInfo)で既存entry/resume要求をone-shot待機する。requestFocus後にframe適用を待ち、window ownerを再確認してからshowする。window focusをeffect keyにせず、Backで意図して隠したIMEをwindow往復だけで再表示しない。独自retry/lifecycle managerや新しい製品判断は導入しない。
