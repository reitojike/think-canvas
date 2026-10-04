# 検証手順: Share Target

[PR前手順](../../docs/runbooks/pre-pr-verification.md)と[PR後手順](../../docs/runbooks/post-pr-convergence.md)を使う。実績は実行後に追記する。

```powershell
.\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -File scripts/check-public-boundary.ps1
git diff --check
pwsh -File scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.share.ShareImportInteractionTest#warmShareUsesCurrentBoardAndWorldCenterWithOneUndoAndOneSave'
pwsh -File scripts/run-windows-gmd.ps1 -ExpectedTestCount 183
```

schema3生成一致、1/2からの移行で既存四要素/board保持を確認。

| scenario | 観測 |
| --- | --- |
| warm/cold、lastなし、picker変更 | 内容/board名、確認前追加0、選択先一要素 |
| URL/改行 | 原文、通常text、world中央、他配置不変 |
| cancel/Back | content/Undo/Redo/save不変 |
| editor/IME/tool/search/gesture/保存制限 | 元操作維持、解除後preview、古いaction拒否 |
| 成功/Undo | 一text、一Undo、一save、Room成功後完了 |
| Activity recreate | 同要求/ack、Running/Failed継続、再追加0 |
| fresh owner + parcel済token + file + Room | 完了照合、未完了manual retry、同patch、一追加 |
| receipt後Undo/delete、UI ack前 | 消した内容の復活0 |
| explicit task終了/new token | 未保存再開0、保存済み保持 |
| 同文fresh share/busy二件目 | 別要求は各一件、busy上書き0 |
| TalkBack/large font | 全文/選択先/確定/取消/失敗/再試行を識別 |
| 画像export/launcher/Back | result取消cleanup/通常操作成立 |

ActivityScenario.recreateはconfig検証でありOS killではない。fresh ownerへのparcel checkpointは復元protocol検証と明記。実OS kill/全機能実機横断確認は親81へ残す。

## 実装中の検証実績

- 初期候補のlocal lint/unit/assemble/assembleAndroidTest成功。unit159件、失敗/error/skip0。
- 初期候補の標準Windows GMD全181件成功。sourceUnchanged=true、manual intervention0、欠落/extra/重複/失敗/error/skip0。fresh XML SHA256: `42FCA947D17FC946B9D5B1F17A90AB98BF008B894C5D5B82EB997E68D255E9ED`。
- 初期focused warm testの二失敗は、ActivityScenarioと起動Intent更新の不一致、およびnative accessibility leafの非clickable actionとして分類・補正済み。上記181件の成功候補は両補正を含む。
- convergeでcheckpoint成功確認、古いpreviewのreceipt照合、一覧IME、document起動flag、空board名、古いCanvas callbackの残差をT021〜T026へ追記。最終候補ではinstrumentation183件を照合する。以下の最終検証・PR/CI/review/mergeは実行後の証跡で判定する。

最終補正候補のlocal basicはlint/unit/assemble/assembleAndroidTestすべて成功。unit159件、失敗/error/skip0。Room schema3の生成前後SHA256一致: `B84C6317A77D6E6D235D910074185536EE0FF3BC15A45E2B3CA1AB223AC3BF53`。追加nativeテストの不要importを修正して完走した。公開境界script233candidate files、diff check成功。

## 183件候補と追加の限定補正

標準Windows GMDはexpected/observed183/183、欠落/extra/重複/error/skip0、sourceUnchanged=true、manual intervention0。182成功/1失敗。XML SHA256: `1FC74046CAC4BADFF2331863FCF78FBD7E70D93DD7A391DA090AF018E8142DED`。

唯一の失敗は既存 `BoardListScreenTest#renameReloadsTheListAfterActivityRecreation` の保存開始 `started.await()` 5000ms。前の181件候補では同テスト成功。新ログはIME表示が失敗区間に継続したが、クリック未到達かadmission拒否かはログだけでは確定しない。製品guardはこの推測で変更しない。T028でnative IME、保存ボタンの安定した画面位置、入力値を前提として観測し、同じnative click・保存開始gate・一回rename/再生成assertを維持する。

#79追加/補正の29件は上記183件で全成功。T027はread-only family checkpointで見つかった未確定destination消失のcheckpoint失敗→Failedへ追加1roundを限定。既存file-failure nativeで同要求保持と明示retryを検証する。最新Roomと新ownerを比較するnative testでは画面読込完了を待ち、fixtureが空snapshotのownerを先に作らないようにする。

最終headの全required CI/canonical/base/threadは実際のPRで確認する。ここまでのlocal成功やCI未実施をMERGE_READYとは呼ばない。

## 最終ローカル候補

T027/T028補正後のlocal basicはlint/unit/assemble/assembleAndroidTest成功。unit159件、失敗/error/skip0。schema3 SHA256は上記と一致。

- 名前変更のfocused: 1件、失敗/error/skip0、sourceUnchanged=true、manual intervention0、FRESH_EXACT_XML。XML SHA256: `13890B39D5FF9E1BA20549AD11B4064219B8C12A48DA65053517D2DAC7FFF476`。native IME/保存位置/入力の前提と、同じ保存開始gate・再生成後一回のRoom renameを確認した。
- file failure/manual retryのfocused: 1件、失敗/error/skip0、sourceUnchanged=true、manual intervention0、FRESH_EXACT_XML。XML SHA256: `D7D879827A7A75D91362114A6C0607F57DBCBC8D30DE6F78031C6D3E5F8DC1F2`。preview準備・未確定destination消失・保存済みterminal更新の各失敗で要求を保持し、修復後の明示retryで回復した。

最終read-only convergeはFR15、SC5、story acceptance14、planの有限surface6とConstitution5原則を照合し、新規の実装残差0件。T001〜T017/T021〜T028を完了。delivery T018〜T020はPR/CI/review/merge/Issue完了の実証待ちで未完了とする。重複するdelivery taskや空のConvergence phaseは追加しない。最終候補の全183件成功は現行headのCI結果で判定する。