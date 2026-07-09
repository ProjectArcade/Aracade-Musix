import re

with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Add accountBackdrop wrapping
# Find: `if (showAccountSheet) {\n        ModalBottomSheet(\n            onDismissRequest = { showAccountSheet = false },\n            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)\n        ) {`
# We will inject the accountBackdrop logic inside the ModalBottomSheet block.

replacement = """        ModalBottomSheet(
            onDismissRequest = { showAccountSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            val accountBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.layerBackdrop(accountBackdrop)) {"""

content = re.sub(r'        ModalBottomSheet\([\s\S]*?\) \{', replacement, content)

# 2. Close the Box before the end of ModalBottomSheet
# Actually, where does ModalBottomSheet end? 
# It ends with } from `if (showAccountSheet) { ModalBottomSheet { ... } }`

replacement2 = """                            )
                        }
                    }
                }
            }
        }
    }"""
content = content.replace("""                            )
                        }
                    }
                }
            }
        }
    }""", replacement2 + "\n            }\n")

# Wait, let's just do a simpler search and replace for the sign in button.
# Remove the first sign-in button
# Search for: `if (currentUser == null) {\n                            OutlinedButton(` down to `} else {\n                            Row(`
pattern = r'if \(currentUser == null\) \{.*?OutlinedButton\(.*?\}\n\s*\} else \{'
content = re.sub(pattern, 'if (currentUser != null) {', content, flags=re.DOTALL)

# Update LiquidButton to use accountBackdrop
content = content.replace("backdrop = mainBackdrop,", "backdrop = accountBackdrop,")

with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "w") as f:
    f.write(content)
