# 調査と設計判断

- CanvasScreenのviewport writerは初期fit、animation、pan/pinch、move auto-pan。委譲先だけをsessionのCompose Stateへ変え、座標式は維持する。
- BoardSessionViewModelのboard別sessionとdiscardの寿命を再利用する。process死亡後はstackを復元しない。
- BoardState.undo/redo前後snapshotを表示補助に使う。Change/保存ロジックへcameraを混ぜる案は責務を増やすため採用しない。
- fittedViewportはResolvedRenderedGeometry.unionで消失前のboundsにも対応可能。textはTextMeasurer、arrowは接続先変更も含む。
- entryはworld中心/scale。size/density変化でもscaleの数値を保持し、新canvas中央へ同じworld中心を写す。densityは2dpの重複許容に使う。
- 上限80、中心の画面差2dp以内かつscale相対差1%以内は重複。同一検索入力groupは最初のoriginへまとめ、他navigationで区切る。
- 正常animation終了、次navigation/native DOWNでの中断は実際の到達点を記録。dispose/resizeは追加しない。世代とorigin引き取りで二重記録を防ぐ。
- manual originは最初のpan/pinch更新時、正常ACTION_UPで一件。move auto-pan/取消を除外。
- Undo表示は最新snapshot/render geometryが揃うcompositionで一回処理。後続編集/明示navigationで古い要求を破棄。
- 視点controlは倍率上方へ48dp Button二つ。表示時はtools columnを上へ移す。既存indicatorのlive guardを再利用し、hidden boundsをhitから除く。
- 新技術選定がないため外部調査不要。現行sourceとmerge済みSpec004/007/008/009を根拠にした。依存/CI/GMD変更なし。
