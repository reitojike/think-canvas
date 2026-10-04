#!/usr/bin/env python3
"""Issue #78 archive integrity check only. No network, builds, tests, or mutation."""
import pathlib,json,hashlib,sys,xml.etree.ElementTree as ET,collections
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def verify(root,checksums=True):
 root=pathlib.Path(root)
 m=json.loads((root/'MANIFEST.json').read_text(encoding='utf-8'))
 actualfiles=sorted(p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file())
 assert actualfiles==m['allArchivedRelativePaths'],'file set mismatch'
 for rec in m['files']:
  path=root/rec['relativePath']; actual=digest(path)
  assert path.stat().st_size==rec['bytes'],rec['relativePath']+' byte-size mismatch'
  assert actual==rec['sha256']==rec['actualSha256'],rec['relativePath']+' archived hash mismatch'
  if rec.get('expectedSha256'): assert actual==rec['expectedSha256'],rec['relativePath']+' source digest mismatch'
 assert len(actualfiles)==m['archiveSize']['fileCount'],'file count mismatch'
 assert sum((root/n).stat().st_size for n in actualfiles)==m['archiveSize']['totalBytes'],'total size mismatch'
 if checksums:
  lines=(root/'SHA256SUMS.txt').read_text(encoding='utf-8').splitlines()
  entries=[line.split('  ',1) for line in lines]
  assert [n for h,n in entries]==[n for n in actualfiles if n!='SHA256SUMS.txt'],'checksum entry set/order mismatch'
  assert all(digest(root/n)==h for h,n in entries),'checksum readback mismatch'
 target=m['testcaseIdentity']; xmlresults=[]
 for run in m['runs']:
  label=run['label']; xml=root/'extracted-authority'/label/'TEST-pixel9Api37.xml'
  suite=ET.parse(xml).getroot(); counts=[int(suite.attrib[k]) for k in ['tests','failures','errors','skipped']]
  cases=list(suite.iter('testcase')); identities=[c.attrib['classname']+'#'+c.attrib['name'] for c in cases]
  assert counts==run['resultCounts'],label+' XML count mismatch'
  assert len(cases)==counts[0] and len(set(identities))==len(cases),label+' testcase identity/count mismatch'
  assert identities.count(target)==1,label+' target not unique'
  failed=[c for c in cases if c.find('failure') is not None]
  assert len(failed)==counts[1],label+' failure count mismatch'
  if label=='failure':
   assert [c.attrib['classname']+'#'+c.attrib['name'] for c in failed]==[target],'unexpected failed identity'
   text=ET.tostring(failed[0],encoding='unicode')
   assert '1465.4' in text and '1017.4' in text and 'EdgeAutoPanTest.kt:507' in text,'historical stack mismatch'
  if label=='diagnostic': assert identities==[target] and counts==[1,0,0,0]
  xmlresults.append({'label':label,'counts':counts,'targetCount':1,'sha256':digest(xml)})
 d=root/'extracted-authority'/'diagnostic'
 events=[json.loads(s) for s in (d/'edge-auto-pan-observation.jsonl').read_text(encoding='utf-8').splitlines() if s]
 assert len(events)==266,'JSONL event count mismatch'
 assert [e['sequence'] for e in events]==list(range(1,267)),'JSONL sequence mismatch'
 summaries=[e for e in events if e['event']=='RECORDER_SUMMARY']
 assert len(summaries)==1 and summaries[0]['droppedEvents']==0,'dropped events'
 counts=collections.Counter(e['event'] for e in events)
 for n in ['TEST_START','DOWN_SENT','MOVE_TO_EDGE_SENT','UP_SENT','UP_RECEIVED','RELEASE_DELTA_BEFORE_COMMIT','BOARD_COMMIT_AFTER','TEST_END']:
  assert counts[n]==1,n+' count mismatch'
 assert counts['CAPTURE_START']==5 and counts['CAPTURE_END']==5
 gate=json.loads((d/'selector-gate.json').read_text())
 assert gate['valid'] is True and gate['identities']==[target],'selector gate mismatch'
 pngs=sorted(p.name for p in d.glob('*.png')); assert pngs==m['diagnosticPngNames'],'PNG set mismatch'
 assert all((d/n).read_bytes().startswith(bytes([137,80,78,71,13,10,26,10])) for n in pngs),'PNG signature mismatch'
 for patch in ['diagnostic-base.patch','diagnostic-observation.patch']:
  assert (root/'diagnostic-source'/patch).stat().st_size>0,'empty diagnostic patch'
 assert 'evidence' in (root/'README.md').read_text(encoding='utf-8'),'README unreadable'
 assert m['repository']=='reitojike/think-canvas' and m['historicalBaseline']['sha']=='d33ed2e7b6a5242c5821169c642e169a882c1b52'
 return {'result':'PASS','files':len(actualfiles),'bytes':m['archiveSize']['totalBytes'],'checksumEntries':len(actualfiles)-1,'originalZipDigestsVerified':3,'xml':xmlresults,'jsonlEvents':266,'droppedEvents':0,'gestureCount':1,'pngCount':5,'diagnosticPatchesNonEmpty':True,'manifestSha256':digest(root/'MANIFEST.json'),'sha256sumsSha256':digest(root/'SHA256SUMS.txt')}
if __name__=='__main__':
 print(json.dumps(verify(pathlib.Path(sys.argv[1])),ensure_ascii=False,indent=2))

