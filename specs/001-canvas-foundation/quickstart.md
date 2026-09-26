# 検証手順: Canvas Foundation

## 準備

JDK 17 と Android SDK Platform 36 / Build Tools 36.0.0 を用意する。エミュレーターまたは実機に API 26 以上の Android を用意する。`compileSdk` と `targetSdk` は 36 のまま。Android 17 SDK の Preview 評価と 37 への更新判断は Issue #10 を参照する。

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
pwsh -File scripts/check-public-boundary.ps1
git diff --check
```

## 操作確認

1. 初回起動で空のボードが見える。空白をタップし、日本語を入力して確定する。タイトル/本文、墨/朱を切り替え、選択済み要素を再タップして編集する。
2. 空白から、次に要素上から 1 指でパンする。2 指で拡大縮小し、倍率の限界で止まることと相対配置が変わらないことを確認する。
3. 要素を長押しして移動する。左下の Undo/Redo で作成、編集、移動を戻し再適用する。パン・ズーム・選択が履歴に入らないことを確認する。
4. 空白・空白文字のみの Draft を閉じ、要素が増えないことを確認する。
5. ネットワークを切り、確定済み要素を作成・編集する。アプリを完全終了して再起動し、内容、種類、色、配置を確認する。
6. 空・選択・編集中の画面を HTML モックの対象コンポーネントと照合する。タップ位置のインライン入力、キーボード上の編集ツールバー、選択枠と移動ハンドルを確認する。
7. 文字サイズを変更し、TalkBack で主要ボタンと要素の内容・選択状態を確認する。主要ボタンのタッチ領域が 44dp 以上であることを確認する。

保存形と不変条件は [data-model.md](data-model.md)、各操作の期待結果は [interaction.md](contracts/interaction.md) を参照する。
