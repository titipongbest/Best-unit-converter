"""ไล่หาข้อมูลลับในหน่วยความจำของแอปตอนล็อค (mem.bin) – needles มาจากสำเนาต้นฉบับบนเครื่อง CI"""
import sys
from pathlib import Path
out = Path(sys.argv[1]); env = dict(l.split("=", 1) for l in (out / "vars.sh").read_text().split())
M = env["M"]; src = out / "src"
mem = (out / "mem.bin").read_bytes() if (out / "mem.bin").exists() else b""
key = bytes.fromhex((out / "keys.txt").read_text().strip()) if (out / "keys.txt").exists() else b""
def chunks(b, n=6, size=48):
    step = max(1, (len(b) - size) // (n + 1))
    return [b[i:i + size] for i in range(step, len(b) - size, step)][:n]
img = (src / env["IMG"]).read_bytes(); vid = (src / env["VID"]).read_bytes(); txt = (src / env["TXT"]).read_bytes()
rows = []
def chk(label, needles, note=""):
    hits = sum(mem.count(x) for x in needles if x)
    rows.append(f"{'LEAK' if hits else 'ok'}  {label}: {hits}{note}")
rows.append(f"หน่วยความจำที่เก็บได้: {len(mem) >> 20} MiB")
chk("master key", [key] if key else [])
chk("ไบต์ดิบของรูป", chunks(img[2000:]))
chk("ไบต์ดิบของวิดีโอ", chunks(vid[4000:]))
for enc in ("utf-8", "utf-16-le"):
    chk(f"เนื้อหาข้อความ ({enc})", [f"CONTENT{M}T".encode(enc), f"SECOND{M}S".encode(enc)])
chk("ข้อความที่พิมพ์เพิ่ม", [f"EDIT{M}E".encode(), f"NEW{M}W".encode(), f"EDIT{M}E".encode("utf-16-le")], " (ตัวทดสอบพิมพ์เอง – อาจเป็นสำเนาของตัวทดสอบ)")
chk("ชื่อไฟล์", [f"{M}N".encode(), f"{M}N".encode("utf-16-le")], " (ตัวทดสอบรู้ชื่อไฟล์เอง – อาจเป็นสำเนาของตัวทดสอบ)")
(out / "memscan.txt").write_text("\n".join(rows))
print("\n".join(rows))
