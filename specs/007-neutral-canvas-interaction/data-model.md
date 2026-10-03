# Editorと終了要求

- Draft: 既存id/x/y/text/kind/color/sessionId。新規の変更は`text.isNotEmpty()`、既存は保存済み要素とのtext/kind/color差分。位置は編集対象外。
- RegionNameDraft: id/name/originalName/sessionId。`name != originalName`が変更。sessionIdはcopyで保持し再entryで新規発行。TextEditorSessionに置き同process再生成を越えて保持する。
- 破棄確認target: familyとsessionIdを組み合わせたString。同じeditorだけ破棄可能。dismissはtargetだけ解除。
- tools: SpatialTool/InkKind/toolsExpandedをsaved-instance stateへ保持。preview/pointer列は再生成で取消。
- Editing→Back→UnchangedならClosed、ChangedならConfirming。Confirming→Continue/dismiss→Editing、Discard→Closed。save guard中は遷移なし。
- schema/履歴/保存ownership変更なし。新囲みの名前破棄は名前sessionだけを閉じる。
