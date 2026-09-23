# Иконка Melogold

Иконка нарисована в том же иллюстративном стиле, что и иконка Clementine VPN (контур, текстура, один лист),
но в жёлтом цвете, чтобы их нельзя было спутать.

| Файл | Что это |
|---|---|
| `melogold-source.png` | Исходник 1254×1254 на белом фоне |
| `melogold-1024.png` | Готовая квадратная иконка на белом фоне |
| `build.py` | Генератор всех размеров и форматов из исходника |

## Как пересобрать

Нужны Python 3, Pillow, NumPy и SciPy.

```bash
cd branding/icon
python3 build.py melogold-source.png
```

Результат появится в `out/`:
- Android: адаптивная иконка (передний слой, монохромный контур для тематических иконок), иконки для старых
  Android, значок уведомления, иконка для Google Play;
- `Melogold.icon` для Icon Composer (Liquid Glass) и `AppIcon-1024.png` для macOS;
- `.ico` для Windows и набор hicolor для Linux;
- `melogold-icon.png` для README и аватар организации.

Размеры и отступы задаются константами в начале `build.py`.
