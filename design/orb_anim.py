import cairosvg, io, math
from PIL import Image

W, H = 480, 640
CX, CY, R = 240, 250, 230          # orb box like the app (r = half size)
FPS, T = 25, 8.6
MINT=(0x34,0xE0,0xA1); AMBER=(0xFF,0xB5,0x47); IDLE=(0x6F,0x7F,0xA3)
PATH = "M38,76 V40 A4,4 0 0,1 42,36 H58 A14,14 0 0,1 58,64 H50"
PLEN = 36 + 2*math.pi*4/4 + 16 + math.pi*14 + 8   # ≈ 110.3
S = 2.55; GX, GY = 51.5, 60.0                       # glyph scale & centre

def mix(a,b,t): return tuple(round(a[i]+(b[i]-a[i])*t) for i in range(3))
def hexc(c,a=1): return f"rgba({c[0]},{c[1]},{c[2]},{a:.3f})"
def ease(t): t=max(0,min(1,t)); return t*t*(3-2*t)
def clamp(t): return max(0,min(1,t))

TAP, CONN, DONE = 1.2, 1.35, 5.0
def frame(t):
    # --- state
    if t < TAP: phase="idle"
    elif t < DONE: phase="busy"
    else: phase="up"
    k = ease((t-DONE)/0.5) if phase=="up" else 0
    color = IDLE if phase=="idle" else (mix(AMBER,MINT,k) if phase=="up" else AMBER)
    press = 1 - 0.06*math.sin(math.pi*clamp((t-TAP)/0.18)) if TAP<=t<TAP+0.18 else 1
    # trace progress
    if phase=="busy":
        if t < 4.0:
            c = (t-CONN) % 1.325 if t>=CONN else -1
            draw = ease(c/0.95) if c>=0 else 0
            fade = 1 - clamp((c-1.05)/0.25) if c>=0 else 0
        else:
            draw, fade = ease((t-4.0)/0.95), 1
    elif phase=="up": draw, fade = 1, 1
    else: draw, fade = 0, 0
    arrived = draw > 0.985
    # spinner / ring
    spin = ((t-TAP)/1.4*360) % 360
    glow = 0.10 if phase=="idle" else (0.30 + 0.12*k)
    pulse = 1.0
    if phase=="up": pulse = 0.88 + 0.12*(0.5+0.5*math.cos((t-DONE-0.5)/1.5*2*math.pi)) if t>DONE+0.5 else 1
    if phase=="busy": pulse = 0.85 + 0.15*(0.5+0.5*math.sin(t/1.5*2*math.pi))

    ringR = R*0.7; sw = 7
    def arc(a0, sweep):
        a0r, a1r = math.radians(a0), math.radians(a0+sweep)
        x0,y0 = CX+ringR*math.cos(a0r), CY+ringR*math.sin(a0r)
        x1,y1 = CX+ringR*math.cos(a1r), CY+ringR*math.sin(a1r)
        return f'<path d="M{x0:.2f},{y0:.2f} A{ringR},{ringR} 0 0 1 {x1:.2f},{y1:.2f}" fill="none" stroke="{hexc(color)}" stroke-width="{sw*1.4}" stroke-linecap="round"/>'

    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="DejaVu Sans, sans-serif">',
           '<defs>',
           f'<radialGradient id="bg" cx="50%" cy="30%" r="75%"><stop offset="0" stop-color="{hexc(color,0.16)}"/><stop offset="1" stop-color="rgba(0,0,0,0)"/></radialGradient>',
           f'<radialGradient id="glow" cx="{CX}" cy="{CY}" r="{R}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{hexc(color, glow*pulse)}"/><stop offset="1" stop-color="rgba(0,0,0,0)"/></radialGradient>',
           f'<linearGradient id="disc" x1="0" y1="{CY-R*0.6}" x2="0" y2="{CY+R*0.6}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#18233B"/><stop offset="1" stop-color="#0C1322"/></linearGradient>',
           '</defs>',
           '<rect width="100%" height="100%" fill="#070B14"/><rect width="100%" height="100%" fill="url(#bg)"/>',
           f'<g transform="translate({CX},{CY}) scale({press}) translate({-CX},{-CY})">',
           f'<circle cx="{CX}" cy="{CY}" r="{R}" fill="url(#glow)"/>',
           f'<circle cx="{CX}" cy="{CY}" r="{ringR}" fill="none" stroke="{hexc(color,0.16)}" stroke-width="{sw}"/>']
    # unchanged outer spinner: rotating 110° arc while busy; full ring when connected
    if phase=="busy": svg.append(arc(spin, 110))
    if phase=="up":
        if k < 1: svg.append(arc(spin + (t-DONE)/1.4*360, 110 + 250*k))
        else: svg.append(f'<circle cx="{CX}" cy="{CY}" r="{ringR}" fill="none" stroke="{hexc(color)}" stroke-width="{sw*1.4}"/>')
    svg.append(f'<circle cx="{CX}" cy="{CY}" r="{R*0.6}" fill="url(#disc)" stroke="rgba(255,255,255,0.04)" stroke-width="2"/>')

    # --- the P glyph
    g = f'translate({CX},{CY}) scale({S}) translate({-GX},{-GY})'
    svg.append(f'<g transform="{g}" fill="none" stroke-linecap="round" stroke-linejoin="round">')
    base_a = 1.0 if phase=="idle" else 0.22
    svg.append(f'<path d="{PATH}" stroke="{hexc(color, base_a)}" stroke-width="6"/>')
    if draw > 0.002 and fade > 0:
        svg.append(f'<path d="{PATH}" stroke="{hexc(color, fade)}" stroke-width="6" stroke-dasharray="{PLEN*draw:.2f} 400"/>')
        # travelling "packet" head while connecting
        if phase=="busy" and draw < 0.985:
            svg.append(f'<path d="{PATH}" stroke="{hexc((255,255,255), 0.85*fade)}" stroke-width="3" stroke-dasharray="0.01 {PLEN*draw:.2f} 0.01 400" />')
    # start node: lights up as soon as connecting starts, with a ripple
    start_on = phase!="idle"
    sa = 1.0 if start_on else 1.0
    svg.append(f'<circle cx="38" cy="78" r="7" fill="#0B1222" stroke="{hexc(color, sa)}" stroke-width="5"/>')
    if phase=="busy":
        rp = ((t-TAP) % 1.325)/1.325
        svg.append(f'<circle cx="38" cy="78" r="{7+9*rp:.2f}" fill="none" stroke="{hexc(color, 0.55*(1-rp))}" stroke-width="2"/>')
    # end node: dim until the trace reaches it, then lights (pop on connect)
    if phase=="idle": ec, ea, er = color, 1.0, 6
    elif phase=="busy":
        ec, ea, er = color, (fade if arrived else 0.22), 6 + (1.2 if arrived else 0)
    else:
        pop = clamp((t-DONE)/0.45); er = 6 * (1 + 0.45*math.sin(math.pi*pop)); ec, ea = color, 1.0
    svg.append(f'<circle cx="47" cy="64" r="{er:.2f}" fill="{hexc(ec, ea)}" stroke="none"/>')
    if phase=="up" and t < DONE+0.9:
        rp = clamp((t-DONE)/0.9)
        svg.append(f'<circle cx="47" cy="64" r="{6+14*rp:.2f}" fill="none" stroke="{hexc(color, 0.6*(1-rp))}" stroke-width="2"/>')
    svg.append('</g></g>')

    # --- labels like the app
    title, sub, subc = {"idle":("Not connected","Tap to connect",(0x85,0x92,0xAD)),
                        "busy":("Connecting","Signing in" if t<2.8 else ("Getting tunnel settings" if t<4.0 else "Opening tunnel"),(0x85,0x92,0xAD)),
                        "up":("Protected","10.212.134.200",color)}[phase]
    svg.append(f'<text x="{CX}" y="{CY+R+70}" text-anchor="middle" fill="#E8EDF7" font-size="34" font-weight="bold">{title}</text>')
    svg.append(f'<text x="{CX}" y="{CY+R+108}" text-anchor="middle" fill="{hexc(subc)}" font-size="19" {"font-family=\"DejaVu Sans Mono\"" if phase=="up" else ""}>{sub}</text>')
    if phase=="idle" and t > 0.5:
        a = clamp((t-0.5)/0.3) * (1-clamp((t-TAP+0.05)/0.1))
        svg.append(f'<circle cx="{CX+60}" cy="{CY+70}" r="22" fill="rgba(255,255,255,{0.22*a:.2f})" stroke="rgba(255,255,255,{0.5*a:.2f})" stroke-width="2"/>')
    svg.append('</svg>')
    png = cairosvg.svg2png(bytestring="".join(svg).encode())
    return Image.open(io.BytesIO(png)).convert("RGB")

frames = [frame(i/FPS) for i in range(int(T*FPS))]
frames[0].save("orb-animation.gif", save_all=True, append_images=frames[1:], duration=int(1000/FPS), loop=0, optimize=True)
# stills: idle / connecting (mid-trace) / connected
st = [frame(0.6), frame(1.35+0.55), frame(4.0+0.6), frame(7.2)]
sheet = Image.new("RGB", (W*4+30, H), (40,40,40))
for i,im in enumerate(st): sheet.paste(im, (i*(W+10), 0))
sheet.save("orb-states.png")
print(len(frames))
