import re

with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Remove the first Sign-In Button
pattern_sign_in = r'if \(currentUser == null\) \{\s*OutlinedButton\(\s*onClick = \{\s*if \(isSigningIn\) return@OutlinedButton[\s\S]*?modifier = Modifier\.fillMaxWidth\(\)\.height\(50\.dp\)\s*\) \{\s*Text\("Sign In with Google"\)\s*\}\s*\} else \{'
content = re.sub(pattern_sign_in, 'if (currentUser != null) {', content)

# 2. Add AccountBackdrop
pattern_bs = r'(ModalBottomSheet\([\s\S]*?\) \{)(\n\s*var isSigningIn)'
new_bs = r'\1\n            val accountBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()\n            androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.layerBackdrop(accountBackdrop)) {\2'
content = re.sub(pattern_bs, new_bs, content, count=1)

# 3. Add closing brace for the Box inside the ModalBottomSheet
# Find the end of AnimatedContent.
pattern_end_bs = r'(\s*Button\(\s*onClick = \{ showDeleteConfirmDialog = true \}.*?Text\("Delete Account".*?\)\s*\}\s*\n\s*\}\n\s*\})'
content = re.sub(pattern_end_bs, r'\1\n            }', content, count=1)

# 4. Replace mainBackdrop with accountBackdrop for the components inside the Account Sheet
pattern_liquid = r'(androidx\.compose\.animation\.AnimatedContent\([\s\S]*?\} screen ->[\s\S]*?)(val currentRoute = navBackStackEntry)'
def replace_backdrop(m):
    return m.group(1).replace('backdrop = mainBackdrop,', 'backdrop = accountBackdrop,') + m.group(2)
content = re.sub(pattern_liquid, replace_backdrop, content, count=1)

# 5. Fix margins
content = content.replace(
    'padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp)',
    'padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)'
)

# Also fix the Profile Row padding to look professional
# Currently: modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBg).padding(10.dp)
# Change to padding(16.dp)
content = content.replace('.background(cardBg)\n                                    .padding(10.dp), // reduced padding', '.background(cardBg)\n                                    .padding(16.dp),')


with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "w") as f:
    f.write(content)
