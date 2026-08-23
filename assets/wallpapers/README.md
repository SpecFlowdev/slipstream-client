# Your own wallpapers

Drop image files here (`.png`, `.jpg`, `.webp`, `.gif`, `.bmp`) and run:

```sh
npm run build && node scripts/screenshots.mjs
```

Each one gets its own capture of the traffic panel, written to
`assets/screenshot-wallpaper-<filename>.png`, which you can then reference
from the README.

Nothing is shipped here on purpose. Wallpapers are usually somebody else's
artwork or photograph, and this repository is Apache-2.0 — so images go in
only if you have the right to publish them. Files in this directory are
ignored by git for the same reason; commit one deliberately with
`git add -f` if it is yours to publish.
