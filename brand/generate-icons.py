import subprocess, pathlib, sys

OUT = pathlib.Path(sys.argv[1])

# One mark, 100-unit box: a heart carrying an ECG pulse.
HEART = "M50 86C50 86 12 60 12 36C12 21 24 12 36 12C43 12 48 16 50 21C52 16 57 12 64 12C76 12 88 21 88 36C88 60 50 86 50 86Z"
PULSE = "M23 46H35L41 33L50 60L58 40L64 46H77"

def svg(size, bg0, bg1, heart, pulse, scale=0.62, cy=0.5):
    s = size * scale
    x = (size - s) / 2
    y = size * cy - s / 2
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="{bg0}"/><stop offset="1" stop-color="{bg1}"/></linearGradient></defs>
<rect width="{size}" height="{size}" fill="url(#g)"/>
<g transform="translate({x} {y}) scale({s/100})">
<path d="{HEART}" fill="{heart}"/>
<path d="{PULSE}" fill="none" stroke="{pulse}" stroke-width="8"
      stroke-linecap="round" stroke-linejoin="round"/>
</g></svg>'''

VARIANTS = {
    "icon-light": ("#3730A3", "#7C3AED", "#FFFFFF", "#4C1D95"),
    "icon-dark":  ("#1E1B4B", "#4C1D95", "#F5F3FF", "#2E1065"),
    # Tinted is graded in grayscale; the system applies the user's tint.
    "icon-tinted": ("#1C1C1E", "#48484A", "#E5E5EA", "#3A3A3C"),
}

OUT.mkdir(parents=True, exist_ok=True)
for name, (b0, b1, h, p) in VARIANTS.items():
    src = OUT / f"{name}.svg"
    src.write_text(svg(1024, b0, b1, h, p))
    subprocess.run(["rsvg-convert", "-w", "1024", "-h", "1024",
                    "-o", str(OUT / f"{name}.png"), str(src)], check=True)
print("generated", *(p.name for p in sorted(OUT.glob("*.png"))))
