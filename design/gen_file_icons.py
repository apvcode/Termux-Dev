#!/usr/bin/env python3
"""
Builds the file-type icons of the attach UI as Android vector drawables and the Kotlin table that
maps a file extension to one of them.

 * Icons that Tabler (MIT, see NOTICE) ships as file-type-* / file-* are converted as they are.
 * The other formats are composed in the same style: the page outline of file-type-* with the
   extension spelled in strokes below it. Letter shapes are copied from Tabler's own file-type icons
   (D, O, C, X, B, M, P, F, S, V, G, T, Z, I) and drawn in the same manner for the rest.

Usage: gen_file_icons.py <dir with downloaded tabler svg files> <app/src/main>
"""
import re, sys, os

SVG_DIR, MAIN = sys.argv[1], sys.argv[2]
DRAWABLE = os.path.join(MAIN, "res", "drawable")
KOTLIN = os.path.join(MAIN, "java", "com", "apvcode", "devx", "ui", "components", "FileIcons.kt")

STYLE = 'android:fillColor="#00000000" android:strokeColor="#FFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round"'
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Tabler Icons (MIT), see NOTICE: %s -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'

PAGE = ["M14 3v4a1 1 0 0 0 1 1h4", "M5 12v-7a2 2 0 0 1 2 -2h7l5 5v4"]

# glyph: (width, [paths]) in a box that is `width` wide and 6 high (origin top-left)
G = {
 "A": (3, ["M0 6l1.5 -6l1.5 6", "M.5 4h2"]),
 "B": (3, ["M0 6h1.5a1.5 1.5 0 0 0 0 -3h-1.5h1.5a1.5 1.5 0 0 0 0 -3h-1.5v6"]),
 "C": (3, ["M3 1.5a1.5 1.5 0 0 0 -3 0v3a1.5 1.5 0 0 0 3 0"]),
 "D": (3, ["M0 0v6h1a2 2 0 0 0 2 -2v-2a2 2 0 0 0 -2 -2h-1"]),
 "E": (3, ["M3 0h-3v6h3", "M0 3h2"]),
 "F": (3, ["M3 0h-3v6", "M0 3h2"]),
 "G": (3, ["M3 0h-1a2 2 0 0 0 -2 2v2a2 2 0 0 0 2 2h1v-3"]),
 "H": (3, ["M0 0v6", "M3 0v6", "M0 3h3"]),
 "I": (0, ["M0 0v6"]),
 "J": (3, ["M3 0v4a1.5 1.5 0 0 1 -3 0"]),
 "K": (3, ["M0 0v6", "M3 0l-3 3l3 3"]),
 "L": (3, ["M0 0v6h3"]),
 "M": (4.5, ["M0 6v-6l2.25 3l2.25 -3v6"]),
 "N": (3, ["M0 6v-6l3 6v-6"]),
 "O": (3, ["M1.5 0a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -3 0v-3a1.5 1.5 0 0 1 1.5 -1.5"]),
 "P": (3, ["M0 3h1.5a1.5 1.5 0 0 0 0 -3h-1.5v6"]),
 "R": (3, ["M0 6v-6h1.5a1.5 1.5 0 0 1 0 3h-1.5", "M1.5 3l1.5 3"]),
 "S": (3, ["M0 5.25c0 .414 .336 .75 .75 .75h1.25a1 1 0 0 0 1 -1v-1a1 1 0 0 0 -1 -1h-1a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1h1.25a.75 .75 0 0 1 .75 .75"]),
 "T": (3, ["M0 0h3", "M1.5 0v6"]),
 "U": (3, ["M0 0v4a1.5 1.5 0 0 0 3 0v-4"]),
 "V": (4, ["M0 0l2 6l2 -6"]),
 "W": (4.5, ["M0 0l1.1 6l1.15 -4l1.15 4l1.1 -6"]),
 "X": (3, ["M0 0l3 6", "M0 6l3 -6"]),
 "Y": (3, ["M0 0l1.5 3l1.5 -3", "M1.5 3v3"]),
 "Z": (3, ["M0 0h3l-3 6h3"]),
 "1": (2, ["M0 1.5l1.5 -1.5v6"]),
 "2": (3, ["M0 1.5a1.5 1.5 0 0 1 3 0c0 1.5 -3 2.5 -3 4.5h3"]),
 "3": (3, ["M0 0h3l-2 2.5a1.75 1.75 0 1 1 -1.5 2.9"]),
 "4": (3, ["M2.25 6v-6l-2.25 4h3"]),
 "7": (3, ["M0 0h3l-1.5 6"]),
}

# extension(s) -> label spelled on the icon
LABELS = {
 ("7z",): "7Z", ("rar",): "RAR", ("tar",): "TAR", ("gz", "tgz"): "GZ", ("bz2",): "BZ", ("xz",): "XZ", ("iso",): "ISO", ("dmg",): "DMG",
 ("json", "jsonl", "geojson"): "JSON", ("yml", "yaml"): "YML", ("toml",): "TOML", ("ini",): "INI", ("log",): "LOG", ("env",): "ENV",
 ("cfg", "conf"): "CFG", ("lock",): "LOCK", ("tsv",): "TSV", ("db", "sqlite", "sqlite3", "db3"): "DB", ("mdb", "accdb"): "MDB",
 ("md", "markdown"): "MD", ("rtf",): "RTF", ("odt",): "ODT", ("ods",): "ODS", ("odp",): "ODP", ("epub",): "EPUB", ("tex",): "TEX",
 ("xlsx", "xlsm"): "XLSX", ("pptx",): "PPTX", ("srt",): "SRT", ("vtt",): "VTT",
 ("py", "pyw"): "PY", ("java",): "JAVA", ("kt", "kts"): "KT", ("c",): "C", ("cpp", "cc", "cxx"): "CPP", ("h", "hpp"): "H", ("cs",): "CS",
 ("go",): "GO", ("rb",): "RB", ("sh", "bash", "zsh"): "SH", ("bat", "cmd"): "BAT", ("ps1",): "PS1", ("lua",): "LUA", ("r",): "R", ("dart",): "DART",
 ("gif",): "GIF", ("webp",): "WEBP", ("heic", "heif"): "HEIC", ("tif", "tiff"): "TIF", ("ico",): "ICO", ("psd",): "PSD", ("ai",): "AI", ("eps",): "EPS",
 ("mp3",): "MP3", ("wav",): "WAV", ("flac",): "FLAC", ("ogg", "oga"): "OGG", ("m4a",): "M4A", ("aac",): "AAC", ("opus",): "OPUS",
 ("mp4", "m4v"): "MP4", ("mov",): "MOV", ("avi",): "AVI", ("mkv",): "MKV", ("webm",): "WEBM", ("3gp",): "3GP",
 ("apk", "aab"): "APK", ("exe",): "EXE", ("msi",): "MSI", ("ttf",): "TTF", ("otf",): "OTF", ("woff", "woff2"): "WOFF",
}

# Tabler's own file-type icons
OFFICIAL = {
 "bmp": ["bmp"], "css": ["css", "scss", "sass", "less"], "csv": ["csv"], "doc": ["doc"], "docx": ["docx", "docm"],
 "html": ["html", "htm", "xhtml"], "jpg": ["jpg", "jpeg", "jfif"], "js": ["js", "mjs", "cjs"], "jsx": ["jsx"], "pdf": ["pdf"],
 "php": ["php"], "png": ["png"], "ppt": ["ppt"], "rs": ["rs"], "sql": ["sql"], "svg": ["svg"], "ts": ["ts"], "tsx": ["tsx"],
 "txt": ["txt", "text"], "vue": ["vue"], "xls": ["xls"], "xml": ["xml", "plist"], "zip": ["zip", "jar", "war"],
}
# General Tabler file icons, for whole families
GENERIC = {
 "code": ("file-code", ["swift", "gradle", "properties", "makefile", "dockerfile", "kts", "scala", "pl", "m", "mm", "asm", "vb", "hs", "ex", "exs", "clj", "ps", "sol", "proto", "graphql", "gql"]),
 "music": ("file-music", ["mid", "midi", "wma", "amr"]),
 "spreadsheet": ("file-spreadsheet", ["numbers", "xlsb"]),
 "database": ("file-database", ["sql3", "dump", "bak", "parquet", "mdf"]),
 "vector": ("file-vector", ["sketch", "fig", "cdr", "xd", "dxf"]),
 "typography": ("file-typography", ["eot"]),
 "three_d": ("file-3d", ["obj", "stl", "fbx", "gltf", "glb", "blend", "3ds", "dae", "step", "stp"]),
 "archive": ("file-zip", ["zst", "lz", "lzma", "cab", "z", "arj", "cpio"]),
 "text": ("file-text", ["pages", "wps", "wpd", "abw"]),
 "unknown": ("file-unknown", []),
}

def path_data(svg):
    ds = re.findall(r'<path\s+d="([^"]+)"', svg)
    return ds

def write(name, comment, body):
    with open(os.path.join(DRAWABLE, "ft_%s.xml" % name), "w") as f:
        f.write(HEAD % comment + body + "</vector>\n")

def pth(d, indent="    "):
    return '%s<path android:pathData="%s" %s />\n' % (indent, d, STYLE)

made = {}   # icon name -> comment

# 1. official file-type-* icons
for name in OFFICIAL:
    svg = open(os.path.join(SVG_DIR, "file-type-%s.svg" % name)).read()
    write(name, "file-type-%s" % name, "".join(pth(d) for d in path_data(svg)))
    made[name] = True
# 2. generic ones
for key, (src, _) in GENERIC.items():
    svg = open(os.path.join(SVG_DIR, "%s.svg" % src)).read()
    write(key, src, "".join(pth(d) for d in path_data(svg)))
    made[key] = True

# 3. composed labels
def compose(label):
    letters = [G.get(c) for c in label]
    if any(l is None for l in letters):
        return None
    widths = [w for w, _ in letters]
    n = len(letters)
    base = 3.0 if n <= 3 else 2.5
    gap = base if n == 1 else min(base, (21.0 - sum(widths)) / (n - 1))
    if n > 1 and gap < 2.0:
        return None
    total = sum(widths) + gap * (n - 1)
    x = 12.5 - total / 2.0
    body = "".join(pth(d) for d in PAGE)
    for (w, paths) in letters:
        body += '    <group android:translateX="%s" android:translateY="15">\n' % ("%g" % round(x, 2))
        body += "".join(pth(d, "        ") for d in paths)
        body += "    </group>\n"
        x += w + gap
    return body

ext_to_icon = {}
for exts, label in LABELS.items():
    body = compose(label)
    if body is None:
        print("skipped label", label)
        continue
    name = "l_" + label.lower()
    write(name, "%s (composed in the style of file-type-*)" % label, body)
    for e in exts:
        ext_to_icon[e] = name
for icon, exts in OFFICIAL.items():
    for e in exts:
        ext_to_icon[e] = icon
for icon, (_, exts) in GENERIC.items():
    for e in exts:
        ext_to_icon.setdefault(e, icon)

# 4. the Kotlin side
lines = []
for e in sorted(ext_to_icon):
    lines.append('    "%s" to R.drawable.ft_%s,' % (e, ext_to_icon[e]))
with open(KOTLIN, "w") as f:
    f.write('''package com.apvcode.devx.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.apvcode.devx.R

// GENERATED by design/gen_file_icons.py: do not edit by hand.

private val byExtension: Map<String, Int> = mapOf(
%s
)

/** The icon of a file by its name: its own for the many formats that have one, a plain "unknown file" otherwise. */
@DrawableRes
fun fileIconRes(fileName: String): Int {
    val extension = fileName.substringAfterLast('.', fileName).lowercase()
    return byExtension[extension] ?: R.drawable.ft_unknown
}

@Composable
fun fileIcon(fileName: String): ImageVector = ImageVector.vectorResource(fileIconRes(fileName))
''' % "\n".join(lines))
print("icons:", len([f for f in os.listdir(DRAWABLE) if f.startswith("ft_")]), "extensions:", len(ext_to_icon))
