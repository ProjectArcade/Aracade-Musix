import os

main_path = "app/src/main/java/com/arcadesoftware/musix/MainActivity.kt"
with open(main_path, "r") as f:
    text = f.read()

old_draw = """                                drawCircle(
                                    color = Color.Black,
                                    radius = radius,
                                    center = Offset(0f, size.height),
                                    blendMode = BlendMode.Clear
                                )"""

new_draw = """                                drawCircle(
                                    color = Color.Black,
                                    radius = radius,
                                    center = Offset(size.width, size.height / 2f),
                                    blendMode = BlendMode.Clear
                                )
                                // Add a colored border to make the circle more prominent
                                drawCircle(
                                    color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.3f),
                                    radius = radius,
                                    center = Offset(size.width, size.height / 2f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 12f)
                                )"""

text = text.replace(old_draw, new_draw)

with open(main_path, "w") as f:
    f.write(text)

