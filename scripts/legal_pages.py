"""Builds site/terms.html and site/privacy.html from the app's LegalScreens.kt, so the site and the app say the same thing."""
import html, re, sys
root = sys.argv[1]
src = open(f"{root}/app/src/main/java/com/melhive/zed/ui/screens/LegalScreens.kt").read()
url = re.search(r'PROJECT_URL = "([^"]+)"', src).group(1)

def kstr(s):
    return s.replace('\\"', '"').replace("$PROJECT_URL", url)

def doc(name):
    block = src[src.index(f"private val {name} = LegalDoc("):]
    block = block[:block.index("\n)\n")]
    title = re.search(r'title = "((?:[^"\\]|\\.)*)"', block).group(1)
    updated = re.search(r'updated = "((?:[^"\\]|\\.)*)"', block).group(1)
    intro = re.search(r'intro = "((?:[^"\\]|\\.)*)"', block).group(1)
    sections = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)" to listOf\((.*?)\),\n', block, re.S):
        paras = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))
        sections.append((kstr(m.group(1)), [kstr(p) for p in paras]))
    return kstr(title), updated, kstr(intro), sections

def linkify(t):
    t = html.escape(t)
    return t.replace(html.escape(url), f'<a href="{url}">{html.escape(url)}</a>')


def page(name, file, other_file, other_title):
    title, updated, intro, sections = doc(name)
    toc = "".join(f'<li><a href="#s{i}">{html.escape(h)}</a></li>' for i, (h, _) in enumerate(sections))
    body = "".join(
        f'<section id="s{i}"><h2>{html.escape(h)}</h2>' + "".join(f"<p>{linkify(p)}</p>" for p in ps) + "</section>"
        for i, (h, ps) in enumerate(sections))
    out = f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="description" content="{html.escape(title)} for ZED, the on-device AI assistant for Android.">
<meta name="theme-color" content="#07090d">
<link rel="icon" type="image/png" href="zed-logo.png">
<title>{html.escape(title)} · ZED</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap">
<style>
:root {{ color-scheme: dark; --bg: #07090d; --bg-2: #0b0e14; --panel: #0e121a; --line: rgba(255,255,255,.08); --line-2: rgba(255,255,255,.14);
  --text: #e8edf4; --muted: #8a94a6; --dim: #5b6577; --accent: #5ce1ff; --mono: "JetBrains Mono", ui-monospace, monospace; --sans: "Inter", system-ui, sans-serif; }}
* {{ box-sizing: border-box; }}
body {{ margin: 0; background: var(--bg); color: var(--text); font-family: var(--sans); line-height: 1.7; -webkit-font-smoothing: antialiased; }}
a {{ color: var(--accent); text-decoration: none; }}
a:hover {{ text-decoration: underline; }}
a:focus-visible, button:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 6px; }}
.wrap {{ width: min(1080px, 100% - 32px); margin-inline: auto; }}
.bar {{ position: sticky; top: 0; z-index: 10; background: rgba(7,9,13,.78); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); border-bottom: 1px solid var(--line); }}
.bar .wrap {{ display: flex; align-items: center; gap: 16px; height: 60px; }}
.brand {{ display: flex; align-items: center; gap: 10px; font-weight: 700; letter-spacing: .08em; color: var(--text); }}
.brand img {{ width: 26px; height: 26px; border-radius: 7px; }}
.bar .spacer {{ flex: 1; }}
.bar a.nav {{ color: var(--muted); font-size: 14px; }}
.bar a.nav:hover {{ color: var(--text); text-decoration: none; }}
header.doc {{ padding: 64px 0 36px; border-bottom: 1px solid var(--line); }}
.kicker {{ font-family: var(--mono); font-size: 12.5px; letter-spacing: .12em; text-transform: uppercase; color: var(--accent); }}
h1 {{ font-size: clamp(32px, 4.4vw, 46px); letter-spacing: -.03em; line-height: 1.1; margin: 10px 0 12px; }}
.updated {{ font-family: var(--mono); font-size: 13px; color: var(--dim); }}
.intro {{ font-size: 17px; color: var(--muted); max-width: 720px; margin: 18px 0 0; }}
.layout {{ display: grid; grid-template-columns: 240px 1fr; gap: 56px; padding: 44px 0 80px; }}
nav.toc {{ position: sticky; top: 84px; align-self: start; }}
nav.toc p {{ font-family: var(--mono); font-size: 12px; color: var(--dim); text-transform: uppercase; letter-spacing: .1em; margin: 0 0 10px; }}
nav.toc ol {{ list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; border-left: 1px solid var(--line); }}
nav.toc a {{ display: block; padding-left: 14px; color: var(--muted); font-size: 14px; }}
nav.toc a:hover {{ color: var(--text); text-decoration: none; }}
article section {{ padding: 6px 0 26px; border-bottom: 1px solid var(--line); margin-bottom: 26px; scroll-margin-top: 84px; }}
article section:last-child {{ border-bottom: 0; }}
article h2 {{ font-size: 20px; letter-spacing: -.01em; margin: 0 0 12px; }}
article p {{ color: #c3cad6; font-size: 15.5px; margin: 0 0 12px; max-width: 760px; }}
footer {{ border-top: 1px solid var(--line); padding: 30px 0 44px; color: var(--dim); font-size: 13.5px; }}
footer .wrap {{ display: flex; gap: 18px; flex-wrap: wrap; align-items: center; }}
footer nav {{ margin-left: auto; display: flex; gap: 18px; }}
footer a {{ color: var(--muted); }}
@media (max-width: 820px) {{ .layout {{ grid-template-columns: 1fr; gap: 28px; }} nav.toc {{ position: static; }} .bar a.nav.hide-sm {{ display: none; }} }}
</style>
</head>
<body>
<header class="bar"><div class="wrap">
  <a class="brand" href="./"><img src="zed-logo.png" alt="">ZED</a>
  <div class="spacer"></div>
  <a class="nav hide-sm" href="./">Home</a>
  <a class="nav" href="{other_file}">{html.escape(other_title)}</a>
</div></header>
<main>
  <header class="doc"><div class="wrap">
    <div class="kicker">Legal</div>
    <h1>{html.escape(title)}</h1>
    <div class="updated">Last updated {html.escape(updated)} · The same text is in the app under More</div>
    <p class="intro">{linkify(intro)}</p>
  </div></header>
  <div class="wrap layout">
    <nav class="toc" aria-label="Contents"><p>Contents</p><ol>{toc}</ol></nav>
    <article>{body}</article>
  </div>
</main>
<footer><div class="wrap">
  <span>ZED · A MelHive Project</span>
  <nav aria-label="Footer"><a href="./">Home</a><a href="privacy.html">Privacy</a><a href="terms.html">Terms</a><a href="{url}">GitHub</a></nav>
</div></footer>
</body>
</html>
'''
    open(f"{root}/site/{file}", "w").write(out)
    print(file, len(sections), "sections")

page("TERMS", "terms.html", "privacy.html", "Privacy Policy")
page("PRIVACY", "privacy.html", "terms.html", "Terms of Use")
