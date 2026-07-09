import re

with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "r") as f:
    content = f.read()

# Replace the top Sign In button logic
# Search for `if (currentUser == null) { ... OutlinedButton ... } else { Row`
pattern_sign_in = r'if \(currentUser == null\) \{.*?OutlinedButton\(.*?modifier = Modifier\.fillMaxWidth\(\)\.height\(50\.dp\)\n\s*\) \{\n\s*Text\("Sign In with Google"\)\n\s*\}\n\s*\} else \{'
new_sign_in = 'if (currentUser != null) {'
content = re.sub(pattern_sign_in, new_sign_in, content, flags=re.DOTALL)

# Inject accountBackdrop and Box wrap inside ModalBottomSheet
# Find `ModalBottomSheet(...) {` up to `var isSigningIn`
pattern_bs = r'(ModalBottomSheet\([\s\S]*?\) \{)(\n\s*var isSigningIn)'
new_bs = r'\1\n            val accountBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()\n            androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.layerBackdrop(accountBackdrop)) {\2'
content = re.sub(pattern_bs, new_bs, content, count=1)

# Find the closing brace for the if(showAccountSheet). It's at the end of the file or near it.
# Actually, I'll use a safer approach for closing the Box.
# I'll just find the exact closing braces of `if (showAccountSheet) { ModalBottomSheet { Box {`
# The original code has:
"""
                            )
                        }
                    }
                }
            }
        }
    }
"""
# Replace with:
"""
                            )
                        }
                    }
                }
            }
            }
        }
    }
"""
content = content.replace("""                            )
                        }
                    }
                }
            }
        }
    }""", """                            )
                        }
                    }
                }
            }
            }
        }
    }""")

# Replace mainBackdrop with accountBackdrop for LiquidToggle and LiquidButton inside Account Sheet
# It's safer to just replace them manually for the 5 lines.
content = content.replace('backdrop = mainBackdrop, onSelect = {', 'backdrop = accountBackdrop, onSelect = {')
content = content.replace('backdrop = mainBackdrop,\n                                modifier = Modifier.fillMaxWidth().height(48.dp)', 'backdrop = accountBackdrop,\n                                modifier = Modifier.fillMaxWidth().height(48.dp)')

# Update setting drawer padding and margin to make it look professional
# Column modifier `modifier = Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),` -> padding 20
content = content.replace(
    'padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp)',
    'padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)'
)

with open("app/src/main/java/com/arcadesoftware/musix/MainActivity.kt", "w") as f:
    f.write(content)
