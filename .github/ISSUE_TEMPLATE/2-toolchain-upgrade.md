---
title: "Upgrade AGP, Kotlin, and Gradle toolchain"
labels: enhancement, tech-debt
---

**Current versions**

| Component | Current | Latest stable |
|-----------|---------|---------------|
| AGP | 8.4.0 | 8.7+ |
| Kotlin | 1.9.24 | 2.1+ |
| Gradle wrapper | (check) | 8.12+ |

**Why**

- Kotlin 2.0+ includes the K2 compiler (faster compilation, stronger type inference)
- AGP updates include build performance improvements and bug fixes
- Room, OkHttp, and other deps have newer versions available

**Migration notes**

- Kotlin 2.0+ requires updating kapt to KSP for Room (or testing K2 with kapt)
- Test K2 compiler flag: `kotlin.compiler.type=K2` in `gradle.properties`
- Check that `compileSdk` / `targetSdk` should also bump to 35 if targeting current platform