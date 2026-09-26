# 調査と判断: Canvas Foundation

## Room 3

**判断**: Room 3.0.3 と KSP を採用し、schema を保存する。
**理由**: Issue #2 の保存要件に一致し、Room 3 は coroutine API と schema 検証を提供する。
**比較**: JSON ファイルは初期実装が軽いが、複数要素の更新と将来の migration が難しい。Room 2 は指定された版ではない。
**資料**: [Room 3 リリース](https://developer.android.com/jetpack/androidx/releases/room3)、[Room ガイド](https://developer.android.com/training/data-storage/room)

## Compose gesture

**判断**: 画面全体の pointer input で tap、パン、ピンチ、長押し移動を調停する。標準の gesture 計算 API を利用する。
**理由**: 要素上からの通常パンと長押し移動を区別し、空白 tap と選択解除の優先順位を守るため。
**比較**: 要素ごとの draggable は要素起点パンと競合する。画面全体の transformable 単独では長押し移動を表せない。
**資料**: [Compose gesture ガイド](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)

## 座標と履歴

**判断**: Float の世界座標と viewport の scale/pan を分離し、作成・編集・移動の前後状態を履歴に持つ。
**理由**: 表示操作が保存された配置を変えないことを単体テストで確認できる。Undo/Redo は保存済みの要素集合にだけ作用する。
**比較**: 表示座標を保存するとズームや端末サイズ変更で配置が変わる。イベントの永続化は今回の session 限定履歴には過剰。
