import os
import shutil
from PIL import Image

# Paths
base_dir = r"c:\Users\manup\Documents\GitHub\Android_Apps\SL-POST-APP\app\src\main\res"
icon_path = r"C:\Users\manup\.gemini\antigravity\brain\ebec0e3a-114e-4526-a72c-a6e4859f413b\sl_post_app_icon_1775026705350.png"
splash_path = r"C:\Users\manup\.gemini\antigravity\brain\ebec0e3a-114e-4526-a72c-a6e4859f413b\sl_post_splash_image_1775026721831.png"

# Mipmap sizes
mipmap_sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# 1. Update Icons
if os.path.exists(icon_path):
    img = Image.open(icon_path).convert("RGBA")
    
    for folder, size in mipmap_sizes.items():
        folder_path = os.path.join(base_dir, folder)
        os.makedirs(folder_path, exist_ok=True)
        
        resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
        
        # Save as ic_launcher.png and ic_launcher_round.png
        resized_img.save(os.path.join(folder_path, "ic_launcher.png"), format="PNG")
        resized_img.save(os.path.join(folder_path, "ic_launcher_round.png"), format="PNG")
        
    # Create foreground for adaptive icons if v26 is used
    drawable_path = os.path.join(base_dir, "drawable")
    foreground_img = img.resize((432, 432), Image.Resampling.LANCZOS)
    foreground_img.save(os.path.join(drawable_path, "ic_launcher_foreground.png"), format="PNG")
    
    print("Icons updated successfully.")
else:
    print(f"Icon not found at {icon_path}")

# 2. Add Splash Screen Image
if os.path.exists(splash_path):
    drawable_path = os.path.join(base_dir, "drawable")
    os.makedirs(drawable_path, exist_ok=True)
    
    # Save the splash screen image directly
    shutil.copy(splash_path, os.path.join(drawable_path, "splash_image.png"))
    print("Splash image copied successfully.")
else:
    print(f"Splash image not found at {splash_path}")

# 3. Clean up the xml vectors so Android uses the raster images for now
v26_folder = os.path.join(base_dir, "mipmap-anydpi-v26")
if os.path.exists(v26_folder):
    ic_xml = os.path.join(v26_folder, "ic_launcher.xml")
    ic_round_xml = os.path.join(v26_folder, "ic_launcher_round.xml")
    if os.path.exists(ic_xml):
        os.remove(ic_xml)
    if os.path.exists(ic_round_xml):
        os.remove(ic_round_xml)
    print("Removed v26 adaptive icon XMLs to force raster fallback.")
