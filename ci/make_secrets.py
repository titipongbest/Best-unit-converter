"""สร้างไฟล์ลับต้นฉบับ (รูป/วิดีโอ/ข้อความ) ที่ฝังรหัสสุ่ม – เก็บสำเนาไว้บนเครื่อง CI เพื่อใช้ไล่หาภายหลัง"""
import hashlib, os, random, secrets, subprocess, sys
from pathlib import Path
from PIL import Image
import imageio_ffmpeg
out = Path(sys.argv[1]); src = out / "src"; src.mkdir(parents=True, exist_ok=True)
M = secrets.token_hex(6)
img, vid, txt = f"secret_photo_{M}N.jpg", f"secret_clip_{M}N.mp4", f"secret_note_{M}N.txt"
rnd = random.Random(M)
Image.frombytes("RGB", (640, 480), bytes(rnd.getrandbits(8) for _ in range(640 * 480 * 3))) \
    .save(src / img, quality=95, comment=f"JPGMARK{M}J".encode())
(src / txt).write_text(f"เนื้อหาลับ CONTENT{M}T บรรทัดสอง\nบรรทัดที่สาม SECOND{M}S\n", "utf-8")
subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-loglevel", "error", "-f", "lavfi", "-i",
                f"cellauto=s=320x240:rate=10:seed={int(M[:6], 16)}", "-t", "3", "-metadata", f"title=VIDMETA{M}V",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(src / vid)], check=True)
h = lambda n: hashlib.sha256((src / n).read_bytes()).hexdigest()
(out / "vars.sh").write_text(f"M={M}\nIMG={img}\nVID={vid}\nTXT={txt}\nH_IMG={h(img)}\nH_VID={h(vid)}\n")
print(f"M={M}")
