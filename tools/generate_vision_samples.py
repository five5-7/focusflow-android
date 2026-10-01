#!/usr/bin/env python3
"""Deterministic, synthetic SVG timetable goldens. No user screenshots or OCR claims."""
from pathlib import Path
import hashlib,json,html
ROOT=Path(__file__).resolve().parents[1]/'app/src/test/resources/vision-samples'
ROOT.mkdir(parents=True,exist_ok=True)
W,H=960,820;left,top,cw,rh=120,100,110,52
courses=[('数学',1,1,2,'东1-101'),('有机化学',3,3,4,'西2-209'),('大学物理实验',5,6,8,'东4-212'),('中国改革开放史',7,11,13,'蒙民伟-225')]
variants=['clear']*10+['rotate','perspective','moire','low_contrast','scaled','gridless']+['header_missing','period_missing','cropped_left','ambiguous_headers']+['blank','severe_blur','unrelated','unrecoverable_crop']
manifest=[]
for n,variant in enumerate(variants,1):
 category='clear' if n<=10 else 'recoverable' if n<=16 else 'manual_anchors' if n<=20 else 'reject'
 missing_header=variant in ('header_missing','ambiguous_headers');missing_period=variant in ('period_missing','cropped_left')
 stroke='#bbb' if variant=='low_contrast' else '#444'
 pieces=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">','<rect width="960" height="820" fill="white"/>']
 matrix=[1,0,0,0,1,0,0,0,1]
 if variant=='rotate': transform='rotate(3 480 410)';import math;a=math.radians(3);c,s=math.cos(a),math.sin(a);matrix=[c,-s,480-480*c+410*s,s,c,410-480*s-410*c,0,0,1]
 elif variant=='perspective': transform='matrix(1 0.03 0.05 0.94 -10 8)';matrix=[1,.05,-10,.03,.94,8,0,0,1] # affine recoverable skew; real projective cases remain Batch B
 elif variant=='scaled': transform='translate(80 65) scale(0.8)';matrix=[.8,0,80,0,.8,65,0,0,1]
 else: transform=''
 if category=='reject':
  if variant=='unrelated': pieces.append('<text x="80" y="200" font-size="40">Shopping list: apples, rice, milk</text>')
  elif variant=='severe_blur': pieces.append('<rect x="150" y="200" width="650" height="300" fill="#ddd" rx="80"/>')
  elif variant=='unrecoverable_crop': pieces.append('<rect x="400" y="200" width="100" height="240" fill="#cef"/><text x="405" y="260">Partial title</text>')
 else:
  pieces.append(f'<g transform="{transform}" font-family="sans-serif" font-size="17">')
  if variant!='gridless':
   for col in range(8): x=left+col*cw;pieces.append(f'<path d="M{x} {top}V{top+13*rh}" stroke="{stroke}"/>')
   for row in range(14): y=top+row*rh;pieces.append(f'<path d="M{left} {y}H{left+7*cw}" stroke="{stroke}"/>')
  for day in range(1,8):
   if not missing_header:pieces.append(f'<text x="{left+(day-1)*cw+30}" y="75">周{["一","二","三","四","五","六","日"][day-1]}</text>')
  for period in range(1,14):
   if not missing_period:pieces.append(f'<text x="70" y="{top+(period-1)*rh+30}">{period}</text>')
  if variant=='ambiguous_headers':pieces.append('<text x="200" y="75">周? 周? 周?</text>')
  for title,day,start,end,location in courses:
   x=left+(day-1)*cw+2;y=top+(start-1)*rh+2;h=(end-start+1)*rh-4
   pieces.extend([f'<rect x="{x}" y="{y}" width="106" height="{h}" rx="4" fill="{"#eee" if variant=="low_contrast" else "#d7e9ff"}"/>',f'<text x="{x+4}" y="{y+28}" font-size="13">{html.escape(title)}</text>',f'<text x="{x+4}" y="{y+50}" font-size="12">{location}</text>'])
  pieces.append('<text x="130" y="810" font-size="12">备注：教师、学分、考试日期不得识别为课程</text></g>')
  if variant=='moire':
   for x in range(0,W,7):pieces.append(f'<path d="M{x} 0L{x+80} 820" opacity=".08" stroke="black"/>')
  if variant in ('cropped_left','header_missing','period_missing'):
   # header/number omission is explicit; crop masks only the untrustworthy anchor area.
   if variant=='cropped_left':pieces.append('<rect x="0" y="95" width="118" height="700" fill="white"/>')
 pieces.append('</svg>');raw='\n'.join(pieces).encode();filename=f'{n:02d}-{variant}.svg';(ROOT/filename).write_bytes(raw)
 def point(x,y):return ((matrix[0]*x+matrix[1]*y+matrix[2])/W,(matrix[3]*x+matrix[4]*y+matrix[5])/H)
 gold=[]
 if category!='reject':
  for title,day,start,end,location in courses:
   corners=[point(left+(day-1)*cw,top+(start-1)*rh),point(left+day*cw,top+(start-1)*rh),point(left+(day-1)*cw,top+end*rh),point(left+day*cw,top+end*rh)]
   gold.append(dict(title=title,rawLocation=location,day=None if missing_header else day,startPeriod=None if missing_period else start,endPeriod=None if missing_period else end,box=[min(p[0] for p in corners),min(p[1] for p in corners),max(p[0] for p in corners),max(p[1] for p in corners)],corners=corners))
 manifest.append(dict(id=f'synthetic-{n:02d}',file=filename,sha256=hashlib.sha256(raw).hexdigest(),category=category,variant=variant,width=W,height=H,transform=matrix,expected=gold,expectedDisposition='reject' if category=='reject' else 'requires_manual_anchors' if category=='manual_anchors' else 'review',annotation='synthetic contract fixture; not an executed OCR or image-model result'))
(ROOT/'manifest.json').write_text(json.dumps(dict(version=1,license='CC0 synthetic test fixtures',samples=manifest),ensure_ascii=False,indent=2)+'\n')
print(f'{len(manifest)} synthetic SVG fixtures generated; no model invoked')
