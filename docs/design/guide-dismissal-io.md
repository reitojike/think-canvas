# ガイド完了設定の IO 移動（#191）

## 契約と範囲

Spec005 Story3 / FR008 / FR014 の初回 empty-board guide と一覧からの replay を保つ。
「はじめる」は設定 commit の成功後にだけ閉じる。失敗時は案内とエラーを保持し、
エラーを閉じてから同じ CTA で再試行する。保存前の Back は記録せず閉じる。
実測 ANR は確認しておらず、効果は UI callback から同期 disk commit を除くことに限る。

## 設計比較

| 案 | 評価 |
| --- | --- |
| 同期 commit を維持 | 最小差分だが UI thread を占有する経路が残る |
| apply に置換 | 成功確認を失うため採用しない |
| Activity lifecycleScope と IO だけに変更 | 再生成時の待機・失敗・replay の所有者が途切れるため採用しない |
| 既存 IO キュー + ガイド専用 ViewModel | 採用候補。保存と画面所有者を分離し、再生成を越えて同じ試行を待つ |
| DataStore 全移行 | 本件の責務・差分を超えるため採用しない |

## 実装境界

- CanvasStore.dismissGuide は既存 submit/await の順序列上で commit の Boolean を検査する。
  設定 key、値、失敗例外を保つ。後続 restore はこの保存の後に並ぶ。
- GuideState は純粋な状態遷移だけを持つ。ViewModel は Main thread 上で状態を更新し、
  viewModelScope で一つの保存を所有する。Activity やその callback を保存処理に渡さない。
- 保存待ち中は CTA を無効にし、Back・外側 tap・swipe による Hidden 遷移を止める。
  UI state 更新前の再入も start の状態検査で拒否する。
- 再生成後も表示・保存待ち・エラーは同じ ViewModel に残る。startup の eligibility 読取は
  保持済みガイドを閉じない。成功した owner だけがガイドを閉じる。
- task 終了・process death では画面所有者を破棄する。既にキューに入った設定保存は継続し得る。
  再起動時は従来の永続設定から判定する。process death を越える新たな処理基盤は設けない。
- SharedPreferences は commit 失敗でも process 内値を更新し得る。失敗時の表示と retry は
  その値で成功扱いせず、保持した ViewModel の失敗状態で決める。

## 検証・採用条件

JVM で保存前 dismiss、保存待ちの再入・dismiss 拒否、失敗後 retry を検証する。
Android では実 IO キューの保留中に Main thread 応答、Back、再生成、保存後 close を確認し、
制御した保存失敗でエラーと owner が再生成後も保たれて retry できることを確認する。
既存 #164 の readiness / guide / replay の AC と大フォント CTA テストを維持する。

focused CI は製品変更の owner 境界の検証として一度実行する。#164 の原因再現用 rerun や
新診断は追加しない。通常 CI の成功を過去 failure 原因 UNKNOWN の解消にしない。
#190 の main IME hidden failure は別の valid RED として保持する。

採用には現行 head の基本検証・PR smoke・focused owner 検証と canonical review が必要。
差分の保守コスト（小さな専用 owner と状態遷移の追加）が UI の同期 disk 経路除去に見合うかを、
Draft PR で利用者が判断する。merge は本作業に含めない。
