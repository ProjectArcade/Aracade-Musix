import os
from PIL import Image

mipmap_dir = "app/src/main/res/mipmap-xxxhdpi"
for filename in os.listdir(mipmap_dir):
    if filename.endswith(".png"):
        filepath = os.path.join(mipmap_dir, filename)
        # Convert to WEBP
        webp_path = os.path.join(mipmap_dir, filename.replace(".png", ".webp"))
        with Image.open(filepath) as img:
            img.save(webp_path, "WEBP", quality=80)
        # Remove original PNG
        os.remove(filepath)
        print(f"Compressed {filename} -> {os.path.basename(webp_path)}")

