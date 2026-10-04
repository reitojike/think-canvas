# Issue #78 post-merge failure evidence

このtreeはevidence archiveでありproduct codeではありません。evidence/issue-78-postmerge-failure branchとissue-78-evidence-20261004 tagは保全用で、mainへ絶対にmerge/cherry-pickしません。PRは作成しません。

Repository: reitojike/think-canvas。Issue: https://github.com/reitojike/think-canvas/issues/78
Feature PR: https://github.com/reitojike/think-canvas/pull/87
Diagnostic-only Draft PR: https://github.com/reitojike/think-canvas/pull/89

Historical disposition: NON_REPRODUCIBLE_POST_MERGE_TEST_FAILURE。
historical required GMD failureは実在しました。root causeは未確認です。product/test correctionは正当化されず、exactly-one diagnosticは448px mismatchを再現しませんでした。追加観測はADDITIONAL_OBSERVATION_LOW_VALUEとadjudicateされています。この保存はreopen criteriaに基づく将来の再adjudicationのためだけです。修正済み、confirmed test bug、confirmed infrastructure flake、root cause resolvedを意味しません。

## Authority

- Historical delivered merge d33ed2e7b6a5242c5821169c642e169a882c1b52、tree1179dc2220d0ac7bae5c9c7bbdf8c4af8c7c799fをbranch baseとする。current mainは含めない。
- A pre-merge same-tree success: run37138090347 / job111246643214 / artifact11279573054、128/0/0/0。
- B historical post-merge failure: run37139666885 / job111251296527 / artifact11280401281、128/1/0/0。
- C exactly-one diagnostic: run37163627134 / attempt1 / job111321993654 / artifact11288119741、1/0/0/0、1 target / 1 drag gesture、JSONL266 / dropped0 / PNG5。
- Target: com.thinkcanvas.canvas.EdgeAutoPanTest#multiSelectionTranslatesTextShapeAndFreeArrowTogether。
- B assertion: expected preview X1465.4 / committed X1017.4、差448px、EdgeAutoPanTest.kt:507。
- Diagnostic base343cb7d659073f98103c999c87d8b41de39eb1fb / headccbf58378163fa59bd4b7949378791cf4a0683bd / actual checkoutfa7fdc5dc2328cdf58ba1c8b29a831aa484dc076。full diffをdiagnostic-sourceへ保存。
- selected reports: 5971536596 / 5974636978 / 5974679075 / 5974895079 / 5975226300 / 5975437356。本文は変更しない。
- Reopen criteria authority: https://github.com/reitojike/think-canvas/issues/78#issuecomment-5975437356 （section 8）。

## Layout / provenance

original-artifactsの3 ZIPはoriginal downloaded bytesをそのまま保存し、recompressしていません。extracted-authorityは直接閲覧用のexact ZIP member bytesで、convenient aliasesとactual ZIP member pathsの対応はMANIFEST.jsonに記録します。元のrelative filenameを失いません。

githubにfresh canonical API metadataと指定3 GMD jobsのraw retrieved logsを保存。logsはgh api --allow-escape-sequencesのstdoutをPowerShell7.6.5のnative-byte redirectionで保存し、改行/timestamps/paths/whitespaceをnormalizeしていません。prior raw retrievalともbyte-identicalです。ephemeral signed ChatGPT/OpenAI URLsをauthorityにしません。

MANIFEST.jsonは各fileのsize/hash/source identifiers、source gates、classification historyを記録します。SHA256SUMS.txtは自身以外の全archived files（manifestを含む）をrelative path順にhashします。manifest自身のhashはSHA256SUMS.txtに置き、循環self-hashは作りません。SHA256SUMS自身のhashとremote readback結果はpush後のIssue indexに記録します。

archive commitの変更はevidence/issue-78/**だけです。historical baselineからproduct/test/workflow/Specを変更していません。artifact retention expiry後もこのGit tree/tagでraw bytesを取得できます。Issue closeはpreservation/readback/index gateが確認されるまでpendingであり、このarchiveはclose/cleanupを実行しません。
