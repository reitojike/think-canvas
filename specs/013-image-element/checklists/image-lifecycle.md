# 画像素材/lifecycle要件チェックリスト

**目的**: PR reviewer向けに画像の保存・復元・資源・操作要件の品質を確認する。
**作成日**: 2026-10-05
**対象**: [spec.md](../spec.md)、[plan.md](../plan.md)、[契約](../contracts/image-lifecycle.md)
**Review Ownership**: reviewer所有。`[x]`は要件が明確で十分という判断であり実装完了ではない。

## 完全性と整合性

- [x] CHK001 元URIの消失とコピー失敗で追加0枚になる契約が定義されているか？ [Completeness, FR002/003/019]
- [x] CHK002 同じ素材を使う複製、Undo/Redo、進行保存/出力で削除可能rootが一義的か？ [Clarity, FR008/010, Plan2]
- [x] CHK003 pickerの取消、二重/古いresult、fresh owner復元、receipt済み/未保存が区別されているか？ [Coverage, FR003/009, 契約取り込み]
- [x] CHK004 画像のbounds/包含/矢印/resize/gap規則が既存geometryと矛盾しないか？ [Consistency, FR004〜008, Plan1]
- [x] CHK005 一Undo/一saveと保存失敗retryが第二writerを必要としない形で定義されているか？ [Consistency, FR006/009, Plan2/3]

## 資源とprivacy

- [x] CHK006 copy/source寸法/decode/cache/並列の上限と超過失敗が数値で明示されているか？ [Measurability, FR012/019, Plan technical context]
- [x] CHK007 8向き/透明/対応静止形式と非対応・animatedの扱いが表示と出力で一致しているか？ [Clarity, FR013, Plan4]
- [x] CHK008 全画像出力の対象集合/素材失敗/EXIF非転写とlocal-onlyが明記されているか？ [Coverage, FR011/013/018]

## UXと完了条件

- [x] CHK009 任意説明の空欄/取消/Back破棄確認/Undoとcaptionとの区別がPO Aに一致するか？ [Consistency, FR014/015, US4]
- [x] CHK010 neutral/IME/save/modalと古い画像actionの利用可否を同じlive状態で判断する契約か？ [Coverage, FR016, Plan3/5]
- [x] CHK011 migration・native・現行head CIと代表実機の証跡を別々に判定できるか？ [Measurability, FR017/020, quickstart]
- [x] CHK012 画像Share Target/OCR/AI/crop/回転編集をv1に紛れ込ませない境界が明示されているか？ [Scope, FR018, Assumptions]

## Notes

生成直後は全項目未チェック。reviewerが要件そのものを確認してmarkerを判断する。実装担当は実装の達成を理由にチェックしない。
