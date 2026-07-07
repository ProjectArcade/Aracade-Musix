import os

manifest_path = "app/src/main/AndroidManifest.xml"
with open(manifest_path, "r") as f:
    manifest = f.read()

sketch_alias = """
        <activity-alias
            android:name=".MainActivitySpecial1"
            android:enabled="false"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_special1"
            android:roundIcon="@mipmap/ic_launcher_special1"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>

        <activity-alias
            android:name=".MainActivitySketch"
            android:enabled="false"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_sketch"
            android:roundIcon="@mipmap/ic_launcher_sketch"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>"""

manifest = manifest.replace("""
        <activity-alias
            android:name=".MainActivitySpecial1"
            android:enabled="false"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_special1"
            android:roundIcon="@mipmap/ic_launcher_special1"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>""", sketch_alias)

with open(manifest_path, "w") as f:
    f.write(manifest)


main_path = "app/src/main/java/com/arcadesoftware/musix/MainActivity.kt"
with open(main_path, "r") as f:
    main_text = f.read()

# Update AppIconManager
old_manager = """        val orangeAlias = android.content.ComponentName(context, "$packageName.MainActivityOrange")
        val specialAlias = android.content.ComponentName(context, "$packageName.MainActivitySpecial1")

        val components = listOf(defaultAlias, blueAlias, comicAlias, grad2Alias, miniAlias, orangeAlias, specialAlias)"""

new_manager = """        val orangeAlias = android.content.ComponentName(context, "$packageName.MainActivityOrange")
        val specialAlias = android.content.ComponentName(context, "$packageName.MainActivitySpecial1")
        val sketchAlias = android.content.ComponentName(context, "$packageName.MainActivitySketch")

        val components = listOf(defaultAlias, blueAlias, comicAlias, grad2Alias, miniAlias, orangeAlias, specialAlias, sketchAlias)"""
main_text = main_text.replace(old_manager, new_manager)


# Update UI
old_icons = """                        val icons = listOf(
                            R.mipmap.ic_launcher,
                            R.mipmap.ic_launcher_bluegradient,
                            R.mipmap.ic_launcher_comic1,
                            R.mipmap.ic_launcher_gradient2,
                            R.mipmap.ic_launcher_mini1,
                            R.mipmap.ic_launcher_orange,
                            R.mipmap.ic_launcher_special1
                        )
                        val iconNames = listOf("Default", "Blue", "Comic", "Grad 2", "Mini", "Orange", "Special")"""

new_icons = """                        val icons = listOf(
                            R.mipmap.ic_launcher,
                            R.mipmap.ic_launcher_bluegradient,
                            R.mipmap.ic_launcher_comic1,
                            R.mipmap.ic_launcher_gradient2,
                            R.mipmap.ic_launcher_mini1,
                            R.mipmap.ic_launcher_orange,
                            R.mipmap.ic_launcher_special1,
                            R.mipmap.ic_launcher_sketch
                        )
                        val iconNames = listOf("Default", "Blue", "Comic", "Grad 2", "Mini", "Orange", "Special", "Sketch")"""

main_text = main_text.replace(old_icons, new_icons)

with open(main_path, "w") as f:
    f.write(main_text)


# Setup splash screen themes
os.makedirs("app/src/main/res/values-night", exist_ok=True)

with open("app/src/main/res/values/colors.xml", "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="splash_bg_light">#F2F2F2</color>
    <color name="splash_bg_dark">#0D0D0D</color>
</resources>
""")

with open("app/src/main/res/values/themes.xml", "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Musix" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/splash_bg_light</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
    </style>
</resources>
""")

with open("app/src/main/res/values-night/themes.xml", "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Musix" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/splash_bg_dark</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
    </style>
</resources>
""")

