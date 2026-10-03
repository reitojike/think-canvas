# Data Model: direction indicators

保存entityは追加しない。

- IndicatorTarget: kind SEARCH/SELECTION、idsは非空、boundsはfiniteでleft<=right/top<=bottom、descriptionは意味による対象識別。searchは1ID、selectionは存在する選択IDs。最大2件、単一ID重複はsearchへ統合。
- IndicatorLayout: target、48dp touchRect、arrow angle。visibleな現在のtarget/viewportとchromeだけから導出し、保存しない。
- Projection入力: 正のfinite scale/density、正のcanvas寸法、safeBoundsとobstacles。無効入力または領域不足は空出力。

State: eligible+offscreen→layout、pan/zoom/content→再導出、onscreen/guard/empty→空、navigation→既存viewport animation→onscreenで消滅。selectedIds/BoardSnapshot/historyは変更しない。
