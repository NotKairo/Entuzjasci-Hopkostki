# Third-party notices

## Libraries included in the app

All of these are licensed under the **Apache License, Version 2.0**
(<https://www.apache.org/licenses/LICENSE-2.0>), used unmodified as binary dependencies.

| Library | Copyright |
|---|---|
| [Lottie for Android](https://github.com/airbnb/lottie-android) (`lottie-compose` 6.7.1) | © Airbnb, Inc. |
| Jetpack Compose, AndroidX Core / Activity / Lifecycle / SavedState / DataStore / Palette | © The Android Open Source Project / Google LLC |
| Kotlin standard library, kotlinx.coroutines | © JetBrains s.r.o. and Kotlin contributors |

The Apache-2.0 NOTICE obligation for these is satisfied by this file and by the in-app
*About & licenses* section. No modified copies of their source are distributed.

## Projects studied but **not** included

The following were read for architecture, platform behaviour and documented Android limitations while
planning Galaxy Pulse. **No source code, assets, strings or artwork were copied from any of them.**
Where a license is restrictive or absent, only publicly documented Android platform behaviour was used.

| Project | License found | Use |
|---|---|---|
| [Arnav-Dugad/dynamic-island-android](https://github.com/Arnav-Dugad/dynamic-island-android) | none (all rights reserved) | read only; nothing reused |
| [bguerraDev/DynamicIslandMusic](https://github.com/bguerraDev/DynamicIslandMusic) | MIT with attribution requirement | read only; credited in the app and README |
| [andrasulthan-alt/OmniLand](https://github.com/andrasulthan-alt/OmniLand) | GPL-3.0 | read only; deliberately not reused (copyleft) |
| [kriwinter/bt-popup](https://github.com/kriwinter/bt-popup) | MIT | read only; hidden-API battery reflection deliberately *not* adopted |
| [greensock/GSAP](https://github.com/greensock/GSAP), [darkroomengineering/lenis](https://github.com/darkroomengineering/lenis), [DavidHDev/react-bits](https://github.com/DavidHDev/react-bits) | various (web libraries) | motion-design inspiration only; not dependencies |

Details and reasoning: [`docs/REFERENCES.md`](docs/REFERENCES.md).

## Artwork

All icons, device illustrations, the launcher icon and the Lottie ripple were drawn for this project.
Device illustrations are deliberately generic and do not imitate any manufacturer's product design.
Sample covers used by the preview and test events are generated from gradients at runtime.
