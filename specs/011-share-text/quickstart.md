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
## PR96のUI操作保護補正

最初の公開head `ddf392682727f3205ae7ef1df9676ddd4b9c9994` はAndroid checks run37207905467の両job成功。fresh XML183/183、欠落/extra/重複/失敗/error/skip0、SHA256 `DFE1DD4A9FD74148C99CE1DBEBE9BCFB7AF1E887CC8BC9BCBE6CCDCDD9479D68`。canonical review依頼14:29:58Z、結果14:34:43Z（review5406676027）で非同期宛先照会後のguardとzoom clickの2指摘あり。

read-only family checkpointはMainの各照会/継続とCanvas chrome/navigation/tool/editor/accessibilityを有限surfaceとして照合。検索focusMatchにも同型を確認し、T029/T030の追加1roundをMain直前確認とzoom/search移動guardへ限定した。保存owner・PO決定・pointer dispatch・検索入力の挙動を広げない。share-specific照会関数に新owner/writer/cacheはない。

補正候補local lint/unit/debug/test APK buildは成功。unit161件（新しい非同期競合2件を含む）、失敗/error/skip0、schema3生成SHA256は上記と同じ。補正したnative focusedと、新headの全required CI/canonical reviewはこれから確認する。旧headの成功を新headへ流用しない。
補正後のfocused nativeは二件ともFRESH_EXACT_XML、各1件、失敗/error/skip0、sourceUnchanged=true、manual intervention0。

- captured Canvas/zoom callback: XML SHA256 `F76B890C1AFE9D544AC201F6E6554EF08861A95441423CC3B5126B7BA5DEBC0C`。preview後に旧callbackを実行してもfocus/back-forward/content/Undo不変。
- search/save/deferred tool: XML SHA256 `8F60EBD7AD60DE28744F65F044603641F29D690EAE20A01E568C2905EAFD8410`。Running中の検索callbackでfocus/content不変、Idle後は同callbackで通常移動でき、Deferred中の検索/toolを完了後にpreviewへ進める。

最終read-only convergeはT029/T030を含む有限surfaceで新規実装残差0。T018〜T020は新headのCI/review/merge/Issue完了待ち。

## CIの検索終了/zoom fixture補正

補正head `bd4e34599c8f337d9cf997895cddc4bef2e37b2d` のCI37211169235はbasic成功、fresh native183/183で182成功/1失敗。欠落/extra/重複/error/skip0、XML SHA256 `D04CB4543B4AF51DD9DAFD5ABAFE091755BF9F7F7148F7F7819F8DFF6756B7E2`。#79追加29件は全成功。唯一の失敗は既存SemanticNavigationTestの検索終了後の25%表示待ち。

read-only family checkpointで検索/IME/layout/viewport animationとnative oracleを照合し、追加1roundはT031のfixture前提観測へ限定した。検索終了後のnative IME非表示/inset0とCanvas bounds安定を確認し、同じzoom actionと期待倍率、本文非表示、囲みfocus、保存不変、待機上限を維持する。失敗logにはIME終了があるがanimation中断を直接観測していないため、製品コードを推測で変更しない。

補正focusedは1件、失敗/error/skip0、sourceUnchanged=true、manual intervention0、FRESH_EXACT_XML。XML SHA256 `2DE69B7586292087D84163998E2F0E46702681B815F7F4AFEAC33F2CDAA89315`。local lint/unit161/debug/test APK build成功、schema3 SHA256は上記と一致。新headの必須CI/canonicalとdeliveryは実証待ち。
## CIの初期一覧/Store gate fixture補正

head `4513721bad6b3b50ac056f43c68eaa4df37875d2` のCI37213119426はbasic成功、fresh native183/183で182成功/1失敗。欠落/extra/重複/error/skip0、XML SHA256 `84DE4880B25F00C99234CBD63B3A1CDB66E758D6530BC8B0D4AA54EE6FD8D542`。#79の29件と検索/zoom補正回帰は全成功。唯一の失敗は既存画像共有Loadingテストの初期一覧待ちで、共有操作前だった。

read-only family checkpointでMain startupとserialized Store actor、全instrumentationのgateを照合した。actor全体を止めるfixtureは一箇所で、初期Store readより先にgateが入ると初期一覧を表示できない順序競合がある。下位モデルの独立read-only censusも同じ結論。他のgateは操作/保存固有だった。追加1roundをT032の初期一覧待ち→gate挿入とfinally解除保証へ限定し、製品処理と既存assert/待機上限を維持した。

補正focusedは1件、失敗/error/skip0、sourceUnchanged=true、manual intervention0、FRESH_EXACT_XML。XML SHA256 `8740F10EAD95CA8E913566D258C279A3568F792C19BA526A376EE8ADCB80C26E`。local lint/unit161/debug/test APK build成功、schema3 SHA256は上記と一致。read-only convergeの追加実装残差0、新headの必須CI/canonical/deliveryは実証待ち。