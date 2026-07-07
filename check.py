with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "r") as f:
    text = f.read()
import re
match = re.search(r'object AppIconManager \{.*?\}', text, re.DOTALL)
if match:
    print(match.group(0))
