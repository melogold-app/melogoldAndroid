# Иконка Melogold

Единый векторный исходник иконки для всех платформ Melogold.

| Файл | Что это |
|---|---|
| `melogold.svg` | Основная иконка на белом фоне (iOS, macOS, Google Play) |
| `melogold-transparent.svg` | Иконка без фона (Windows, Linux) |
| `melogold-glyph.svg` | Одноцветный силуэт (тонированные иконки, значок уведомления) |
| `gen.py`, `build.py` | Генератор всех размеров и форматов |

## Как пересобрать

Нужны macOS (рендер SVG через Quick Look), Python 3, Pillow и NumPy.

```bash
cd branding/icon
python3 build.py
```

Результат появится в `out/`: ресурсы Android, `Melogold.icon` для Icon Composer (Liquid Glass),
`.ico` для Windows, набор hicolor для Linux и `preview.png` для проверки.

Цвета и геометрия задаются в `gen.py`, масштабы для платформ — в начале `build.py`.
