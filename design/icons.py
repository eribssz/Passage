import cairosvg
MINT="#34E0A1"; BG="#0B1222"
# Each option: launcher foreground (108 viewport, safe zone ~ 21..87) and QS glyph (24 viewport)
opts = {
 "A · Arches (current)": (
  f'''<g fill="none" stroke="{MINT}" stroke-linecap="round">
   <path stroke-width="5" d="M30,74 L30,52 A24,24 0 0,1 78,52 L78,74"/>
   <path stroke-width="4" stroke-opacity=".6" d="M40,74 L40,55 A14,14 0 0,1 68,55 L68,74"/>
   <path stroke-width="3" stroke-opacity=".3" d="M49,74 L49,58 A5,5 0 0,1 59,58 L59,74"/></g>''',
  '''<g fill="none" stroke="C" stroke-width="2" stroke-linecap="round">
   <path d="M4,20 V11 a8,8 0 0 1 16,0 V20"/><path d="M9,20 V12.5 a3,3 0 0 1 6,0 V20"/></g>'''),
 "B · Doorway": (
  f'''<path d="M33,78 V52 a21,21 0 0 1 42,0 V78" fill="none" stroke="{MINT}" stroke-width="6" stroke-linecap="round"/>
   <circle cx="54" cy="56" r="6.5" fill="{MINT}"/><path d="M54,60 V70" stroke="{MINT}" stroke-width="6" stroke-linecap="round"/>
   <path d="M24,78 H84" stroke="{MINT}" stroke-opacity=".35" stroke-width="3" stroke-linecap="round"/>''',
  '''<path d="M5,21 V11 a7,7 0 0 1 14,0 V21" fill="none" stroke="C" stroke-width="2.2" stroke-linecap="round"/>
   <circle cx="12" cy="12" r="2.4" fill="C"/><path d="M12,13 V17" stroke="C" stroke-width="2.2" stroke-linecap="round"/>'''),
 "C · Route": (
  f'''<g fill="none" stroke="{MINT}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round">
   <path d="M38,76 V40 a4,4 0 0 1 4,-4 H58 a14,14 0 0 1 0,28 H50"/></g>
   <circle cx="38" cy="78" r="7" fill="{BG}" stroke="{MINT}" stroke-width="5"/>
   <circle cx="47" cy="64" r="6" fill="{MINT}"/>''',
  '''<g fill="none" stroke="C" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
   <path d="M7,18.5 V5 H14 a5.5,5.5 0 0 1 0,11 H11"/></g>
   <circle cx="7" cy="19.5" r="2.4" fill="none" stroke="C" stroke-width="2"/><circle cx="10.5" cy="16" r="2" fill="C"/>'''),
 "D · Through the wall": (
  f'''<g stroke-linecap="round"><path d="M58,26 V44 M58,64 V82" stroke="{MINT}" stroke-opacity=".45" stroke-width="7"/>
   <path d="M26,54 H78" stroke="{MINT}" stroke-width="6"/>
   <path d="M70,45 L80,54 L70,63" fill="none" stroke="{MINT}" stroke-width="6" stroke-linejoin="round"/>
   <circle cx="28" cy="54" r="6" fill="{MINT}"/></g>''',
  '''<g stroke-linecap="round"><path d="M15,3 V8.5 M15,15.5 V21" stroke="C" stroke-width="2.4"/>
   <path d="M4,12 H20" stroke="C" stroke-width="2.2"/>
   <path d="M17,8.5 L20.5,12 L17,15.5" fill="none" stroke="C" stroke-width="2.2" stroke-linejoin="round"/>
   <circle cx="4.5" cy="12" r="2.2" fill="C"/></g>'''),
}
W, rowH = 1080, 300
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{60+rowH*len(opts)}" font-family="DejaVu Sans, sans-serif">',
       f'<rect width="100%" height="100%" fill="#05080F"/>',
       '<text x="40" y="44" fill="#8592AD" font-size="20">App icon</text><text x="420" y="44" fill="#8592AD" font-size="20">Quick Settings — off / on</text><text x="860" y="44" fill="#8592AD" font-size="20">Status bar</text>']
for i,(name,(fg,glyph)) in enumerate(opts.items()):
    y = 60 + i*rowH
    svg.append(f'<text x="40" y="{y+30}" fill="#E8EDF7" font-size="24" font-weight="bold">{name}</text>')
    # launcher: squircle 200px
    svg.append(f'<g transform="translate(40,{y+50})"><rect width="200" height="200" rx="60" fill="{BG}"/><g transform="scale({200/108})">{fg}</g></g>')
    # small launcher 96px on light wallpaper
    svg.append(f'<g transform="translate(262,{y+130})"><rect x="-10" y="-10" width="116" height="116" rx="20" fill="#C9D3E3"/><rect width="96" height="96" rx="29" fill="{BG}"/><g transform="scale({96/108})">{fg}</g></g>')
    # QS tiles pill style (Android 13+): off and on
    for j,(tbg,gc,lbl,sub) in enumerate([("#2A2F3A","#E8EDF7","Passage","Off"),(MINT,"#04140D","Passage","Acme HQ")]):
        x = 420 + j*215
        tc = "#E8EDF7" if j==0 else "#04140D"
        svg.append(f'<g transform="translate({x},{y+90})"><rect width="200" height="96" rx="32" fill="{tbg}"/>'
                   f'<g transform="translate(22,24) scale(2)">{glyph.replace("C", gc).replace("Cx","cx") }</g>'
                   f'<text x="84" y="44" fill="{tc}" font-size="20" font-weight="bold">{lbl}</text><text x="84" y="70" fill="{tc}" fill-opacity=".75" font-size="17">{sub}</text></g>')
    # status bar glyph at real-ish size
    svg.append(f'<g transform="translate(860,{y+118})"><rect width="180" height="40" rx="8" fill="#000"/><text x="12" y="28" fill="#fff" font-size="20">14:35</text>'
               f'<g transform="translate(120,9) scale(0.95)">{glyph.replace("C","#FFFFFF")}</g></g>')
svg.append('</svg>')
s="\n".join(svg)
open("icons.svg","w").write(s)
cairosvg.svg2png(bytestring=s.encode(), write_to="Passage-icon-options.png")
